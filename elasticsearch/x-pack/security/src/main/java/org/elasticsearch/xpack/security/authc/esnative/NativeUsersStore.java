/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.esnative;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.DocWriteResponse.Result;
import org.elasticsearch.action.LatchedActionListener;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.ClearScrollRequest;
import org.elasticsearch.action.search.ClearScrollResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.ValidationException;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.gateway.GatewayService;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.engine.DocumentMissingException;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.xpack.security.InternalClient;
import org.elasticsearch.xpack.security.SecurityTemplateService;
import org.elasticsearch.xpack.security.action.realm.ClearRealmCacheRequest;
import org.elasticsearch.xpack.security.action.realm.ClearRealmCacheResponse;
import org.elasticsearch.xpack.security.action.user.ChangePasswordRequest;
import org.elasticsearch.xpack.security.action.user.DeleteUserRequest;
import org.elasticsearch.xpack.security.action.user.PutUserRequest;
import org.elasticsearch.xpack.security.authc.support.Hasher;
import org.elasticsearch.xpack.security.authc.support.SecuredString;
import org.elasticsearch.xpack.security.client.SecurityClient;
import org.elasticsearch.xpack.security.user.SystemUser;
import org.elasticsearch.xpack.security.user.User;
import org.elasticsearch.xpack.security.user.User.Fields;
import org.elasticsearch.xpack.security.user.XPackUser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.xpack.security.Security.setting;
import static org.elasticsearch.xpack.security.SecurityTemplateService.securityIndexMappingAndTemplateUpToDate;

/**
 * NativeUsersStore is a store for users that reads from an Elasticsearch index. This store is responsible for fetching the full
 * {@link User} object, which includes the names of the roles assigned to the user.
 * <p>
 * No caching is done by this class, it is handled at a higher level and no polling for changes is done by this class. Modification
 * operations make a best effort attempt to clear the cache on all nodes for the user that was modified.
 */
public class NativeUsersStore extends AbstractComponent implements ClusterStateListener {

    private static final Setting<Integer> SCROLL_SIZE_SETTING =
            Setting.intSetting(setting("authc.native.scroll.size"), 1000, Property.NodeScope);

    private static final Setting<TimeValue> SCROLL_KEEP_ALIVE_SETTING =
            Setting.timeSetting(setting("authc.native.scroll.keep_alive"), TimeValue.timeValueSeconds(10L), Property.NodeScope);

    public enum State {
        INITIALIZED,
        STARTING,
        STARTED,
        STOPPING,
        STOPPED,
        FAILED
    }

    private static final String USER_DOC_TYPE = "user";
    private static final String RESERVED_USER_DOC_TYPE = "reserved-user";

    private final Hasher hasher = Hasher.BCRYPT;
    private final AtomicReference<State> state = new AtomicReference<>(State.INITIALIZED);
    private final InternalClient client;
    private int scrollSize;
    private TimeValue scrollKeepAlive;

    private volatile boolean securityIndexExists = false;

    public NativeUsersStore(Settings settings, InternalClient client) {
        super(settings);
        this.client = client;
    }

    /**
     * Blocking version of {@code getUser} that blocks until the User is returned
     */
    public User getUser(String username) {
        if (state() != State.STARTED) {
            logger.trace("attempted to get user [{}] before service was started", username);
            return null;
        }
        UserAndPassword uap = getUserAndPassword(username);
        return uap == null ? null : uap.user();
    }

    /**
     * Retrieve a single user, calling the listener when retrieved
     */
    public void getUser(String username, final ActionListener<User> listener) {
        if (state() != State.STARTED) {
            logger.trace("attempted to get user [{}] before service was started", username);
            listener.onFailure(new IllegalStateException("user cannot be retrieved as native user service has not been started"));
            return;
        }
        getUserAndPassword(username, new ActionListener<UserAndPassword>() {
            @Override
            public void onResponse(UserAndPassword uap) {
                listener.onResponse(uap == null ? null : uap.user());
            }

            @Override
            public void onFailure(Exception t) {
                if (t instanceof IndexNotFoundException) {
                    logger.trace("failed to retrieve user [{}] since security index does not exist", username);
                    // We don't invoke the onFailure listener here, instead
                    // we call the response with a null user
                    listener.onResponse(null);
                } else {
                    logger.debug((Supplier<?>) () -> new ParameterizedMessage("failed to retrieve user [{}]", username), t);
                    listener.onFailure(t);
                }
            }
        });
    }

    /**
     * Retrieve a list of users, if usernames is null or empty, fetch all users
     */
    public void getUsers(String[] usernames, final ActionListener<List<User>> listener) {
        if (state() != State.STARTED) {
            logger.trace("attempted to get users before service was started");
            listener.onFailure(new IllegalStateException("users cannot be retrieved as native user service has not been started"));
            return;
        }
        try {
            final List<User> users = new ArrayList<>();
            QueryBuilder query;
            if (usernames == null || usernames.length == 0) {
                query = QueryBuilders.matchAllQuery();
            } else {
                query = QueryBuilders.boolQuery().filter(QueryBuilders.idsQuery(USER_DOC_TYPE).addIds(usernames));
            }
            SearchRequest request = client.prepareSearch(SecurityTemplateService.SECURITY_INDEX_NAME)
                    .setScroll(scrollKeepAlive)
                    .setTypes(USER_DOC_TYPE)
                    .setQuery(query)
                    .setSize(scrollSize)
                    .setFetchSource(true)
                    .request();
            request.indicesOptions().ignoreUnavailable();

            // This function is MADNESS! But it works, don't think about it too hard...
            client.search(request, new ActionListener<SearchResponse>() {

                private SearchResponse lastResponse = null;

                @Override
                public void onResponse(final SearchResponse resp) {
                    lastResponse = resp;
                    boolean hasHits = resp.getHits().getHits().length > 0;
                    if (hasHits) {
                        for (SearchHit hit : resp.getHits().getHits()) {
                            UserAndPassword u = transformUser(hit.getId(), hit.getSource());
                            if (u != null) {
                                users.add(u.user());
                            }
                        }
                        SearchScrollRequest scrollRequest = client.prepareSearchScroll(resp.getScrollId())
                                .setScroll(scrollKeepAlive).request();
                        client.searchScroll(scrollRequest, this);
                    } else {
                        if (resp.getScrollId() != null) {
                            clearScrollResponse(resp.getScrollId());
                        }
                        // Finally, return the list of users
                        listener.onResponse(Collections.unmodifiableList(users));
                    }
                }

                @Override
                public void onFailure(Exception t) {
                    // attempt to clear scroll response
                    if (lastResponse != null && lastResponse.getScrollId() != null) {
                        clearScrollResponse(lastResponse.getScrollId());
                    }

                    if (t instanceof IndexNotFoundException) {
                        logger.trace("could not retrieve users because security index does not exist");
                        // We don't invoke the onFailure listener here, instead just pass an empty list
                        listener.onResponse(Collections.emptyList());
                    } else {
                        listener.onFailure(t);
                    }

                }
            });
        } catch (Exception e) {
            logger.error((Supplier<?>) () -> new ParameterizedMessage("unable to retrieve users {}", Arrays.toString(usernames)), e);
            listener.onFailure(e);
        }
    }

    /**
     * Blocking method to get the user and their password hash
     */
    private UserAndPassword getUserAndPassword(final String username) {
        final AtomicReference<UserAndPassword> userRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        getUserAndPassword(username, new LatchedActionListener<>(new ActionListener<UserAndPassword>() {
            @Override
            public void onResponse(UserAndPassword user) {
                userRef.set(user);
            }

            @Override
            public void onFailure(Exception t) {
                if (t instanceof IndexNotFoundException) {
                    logger.trace(
                            (Supplier<?>) () -> new ParameterizedMessage(
                                    "failed to retrieve user [{}] since security index does not exist", username), t);
                } else {
                    logger.error((Supplier<?>) () -> new ParameterizedMessage("failed to retrieve user [{}]", username), t);
                }
            }
        }, latch));
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.error("timed out retrieving user [{}]", username);
            return null;
        }
        return userRef.get();
    }

    /**
     * Async method to retrieve a user and their password
     */
    private void getUserAndPassword(final String user, final ActionListener<UserAndPassword> listener) {
        try {
            GetRequest request = client.prepareGet(SecurityTemplateService.SECURITY_INDEX_NAME, USER_DOC_TYPE, user).request();
            client.get(request, new ActionListener<GetResponse>() {
                @Override
                public void onResponse(GetResponse response) {
                    listener.onResponse(transformUser(response.getId(), response.getSource()));
                }

                @Override
                public void onFailure(Exception t) {
                    if (t instanceof IndexNotFoundException) {
                        logger.trace(
                                (Supplier<?>) () -> new ParameterizedMessage(
                                        "could not retrieve user [{}] because security index does not exist", user), t);
                    } else {
                        logger.error((Supplier<?>) () -> new ParameterizedMessage("failed to retrieve user [{}]", user), t);
                    }
                    // We don't invoke the onFailure listener here, instead
                    // we call the response with a null user
                    listener.onResponse(null);
                }
            });
        } catch (IndexNotFoundException infe) {
            logger.trace("could not retrieve user [{}] because security index does not exist", user);
            listener.onResponse(null);
        } catch (Exception e) {
            logger.error((Supplier<?>) () -> new ParameterizedMessage("unable to retrieve user [{}]", user), e);
            listener.onFailure(e);
        }
    }

    /**
     * Async method to change the password of a native or reserved user. If a reserved user does not exist, the document will be created
     * with a hash of the provided password.
     */
    public void changePassword(final ChangePasswordRequest request, final ActionListener<Void> listener) {
        final String username = request.username();
        assert SystemUser.NAME.equals(username) == false && XPackUser.NAME.equals(username) == false : username + "is internal!";

        final String docType;
        if (ReservedRealm.isReserved(username, settings)) {
            docType = RESERVED_USER_DOC_TYPE;
        } else {
            docType = USER_DOC_TYPE;
        }

        client.prepareUpdate(SecurityTemplateService.SECURITY_INDEX_NAME, docType, username)
                .setDoc(Fields.PASSWORD.getPreferredName(), String.valueOf(request.passwordHash()))
                .setRefreshPolicy(request.getRefreshPolicy())
                .execute(new ActionListener<UpdateResponse>() {
                    @Override
                    public void onResponse(UpdateResponse updateResponse) {
                        assert updateResponse.getResult() == DocWriteResponse.Result.UPDATED;
                        clearRealmCache(request.username(), listener, null);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        if (isIndexNotFoundOrDocumentMissing(e)) {
                            if (docType.equals(RESERVED_USER_DOC_TYPE)) {
                                createReservedUser(username, request.passwordHash(), request.getRefreshPolicy(), listener);
                            } else {
                                logger.debug((Supplier<?>) () ->
                                        new ParameterizedMessage("failed to change password for user [{}]", request.username()), e);
                                ValidationException validationException = new ValidationException();
                                validationException.addValidationError("user must exist in order to change password");
                                listener.onFailure(validationException);
                            }
                        } else {
                            listener.onFailure(e);
                        }
                    }
                });
    }

    /**
     * Asynchronous method to create a reserved user with the given password hash. The cache for the user will be cleared after the document
     * has been indexed
     */
    private void createReservedUser(String username, char[] passwordHash, RefreshPolicy refresh, ActionListener<Void> listener) {
        client.prepareIndex(SecurityTemplateService.SECURITY_INDEX_NAME, RESERVED_USER_DOC_TYPE, username)
                .setSource(Fields.PASSWORD.getPreferredName(), String.valueOf(passwordHash), Fields.ENABLED.getPreferredName(), true)
                .setRefreshPolicy(refresh)
                .execute(new ActionListener<IndexResponse>() {
                    @Override
                    public void onResponse(IndexResponse indexResponse) {
                        clearRealmCache(username, listener, null);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        listener.onFailure(e);
                    }
                });
    }

    /**
     * Asynchronous method to put a user. A put user request without a password hash is treated as an update and will fail with a
     * {@link ValidationException} if the user does not exist. If a password hash is provided, then we issue a update request with an
     * upsert document as well; the upsert document sets the enabled flag of the user to true but if the document already exists, this
     * method will not modify the enabled value.
     */
    public void putUser(final PutUserRequest request, final ActionListener<Boolean> listener) {
        if (state() != State.STARTED) {
            listener.onFailure(new IllegalStateException("user cannot be added as native user service has not been started"));
            return;
        }

        try {
            if (request.passwordHash() == null) {
                updateUserWithoutPassword(request, listener);
            } else {
                indexUser(request, listener);
            }
        } catch (Exception e) {
            logger.error((Supplier<?>) () -> new ParameterizedMessage("unable to put user [{}]", request.username()), e);
            listener.onFailure(e);
        }
    }

    /**
     * Handles updating a user that should already exist where their password should not change
     */
    private void updateUserWithoutPassword(final PutUserRequest putUserRequest, final ActionListener<Boolean> listener) {
        assert putUserRequest.passwordHash() == null;
        // We must have an existing document
        client.prepareUpdate(SecurityTemplateService.SECURITY_INDEX_NAME, USER_DOC_TYPE, putUserRequest.username())
                .setDoc(User.Fields.USERNAME.getPreferredName(), putUserRequest.username(),
                        User.Fields.ROLES.getPreferredName(), putUserRequest.roles(),
                        User.Fields.FULL_NAME.getPreferredName(), putUserRequest.fullName(),
                        User.Fields.EMAIL.getPreferredName(), putUserRequest.email(),
                        User.Fields.METADATA.getPreferredName(), putUserRequest.metadata(),
                        User.Fields.ENABLED.getPreferredName(), putUserRequest.enabled())
                .setRefreshPolicy(putUserRequest.getRefreshPolicy())
                .execute(new ActionListener<UpdateResponse>() {
                    @Override
                    public void onResponse(UpdateResponse updateResponse) {
                        assert updateResponse.getResult() == DocWriteResponse.Result.UPDATED;
                        clearRealmCache(putUserRequest.username(), listener, false);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        Exception failure = e;
                        if (isIndexNotFoundOrDocumentMissing(e)) {
                            // if the index doesn't exist we can never update a user
                            // if the document doesn't exist, then this update is not valid
                            logger.debug((Supplier<?>) () -> new ParameterizedMessage("failed to update user document with username [{}]",
                                    putUserRequest.username()), e);
                            ValidationException validationException = new ValidationException();
                            validationException.addValidationError("password must be specified unless you are updating an existing user");
                            failure = validationException;
                        }
                        listener.onFailure(failure);
                    }
                });
    }

    private void indexUser(final PutUserRequest putUserRequest, final ActionListener<Boolean> listener) {
        assert putUserRequest.passwordHash() != null;
        client.prepareIndex(SecurityTemplateService.SECURITY_INDEX_NAME,
                USER_DOC_TYPE, putUserRequest.username())
                .setSource(User.Fields.USERNAME.getPreferredName(), putUserRequest.username(),
                        User.Fields.PASSWORD.getPreferredName(), String.valueOf(putUserRequest.passwordHash()),
                        User.Fields.ROLES.getPreferredName(), putUserRequest.roles(),
                        User.Fields.FULL_NAME.getPreferredName(), putUserRequest.fullName(),
                        User.Fields.EMAIL.getPreferredName(), putUserRequest.email(),
                        User.Fields.METADATA.getPreferredName(), putUserRequest.metadata(),
                        User.Fields.ENABLED.getPreferredName(), putUserRequest.enabled())
                .setRefreshPolicy(putUserRequest.getRefreshPolicy())
                .execute(new ActionListener<IndexResponse>() {
                    @Override
                    public void onResponse(IndexResponse updateResponse) {
                        clearRealmCache(putUserRequest.username(), listener, updateResponse.getResult() == DocWriteResponse.Result.CREATED);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        listener.onFailure(e);
                    }
                });
    }

    /**
     * Asynchronous method that will update the enabled flag of a user. If the user is reserved and the document does not exist, a document
     * will be created. If the user is not reserved, the user must exist otherwise the operation will fail.
     */
    public void setEnabled(final String username, final boolean enabled, final RefreshPolicy refreshPolicy,
                           final ActionListener<Void> listener) {
        if (state() != State.STARTED) {
            listener.onFailure(new IllegalStateException("enabled status cannot be changed as native user service has not been started"));
            return;
        }

        if (ReservedRealm.isReserved(username, settings)) {
            setReservedUserEnabled(username, enabled, refreshPolicy, listener);
        } else {
            setRegularUserEnabled(username, enabled, refreshPolicy, listener);
        }
    }

    private void setRegularUserEnabled(final String username, final boolean enabled, final RefreshPolicy refreshPolicy,
                            final ActionListener<Void> listener) {
        try {
            client.prepareUpdate(SecurityTemplateService.SECURITY_INDEX_NAME, USER_DOC_TYPE, username)
                    .setDoc(User.Fields.ENABLED.getPreferredName(), enabled)
                    .setRefreshPolicy(refreshPolicy)
                    .execute(new ActionListener<UpdateResponse>() {
                        @Override
                        public void onResponse(UpdateResponse updateResponse) {
                            assert updateResponse.getResult() == Result.UPDATED;
                            clearRealmCache(username, listener, null);
                        }

                        @Override
                        public void onFailure(Exception e) {
                            Exception failure = e;
                            if (isIndexNotFoundOrDocumentMissing(e)) {
                                // if the index doesn't exist we can never update a user
                                // if the document doesn't exist, then this update is not valid
                                logger.debug((Supplier<?>) () ->
                                        new ParameterizedMessage("failed to {} user [{}]", enabled ? "enable" : "disable", username), e);
                                ValidationException validationException = new ValidationException();
                                validationException.addValidationError("only existing users can be " + (enabled ? "enabled" : "disabled"));
                                failure = validationException;
                            }
                            listener.onFailure(failure);
                        }
                    });
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    private void setReservedUserEnabled(final String username, final boolean enabled, final RefreshPolicy refreshPolicy,
                                        final ActionListener<Void> listener) {
        try {
            client.prepareUpdate(SecurityTemplateService.SECURITY_INDEX_NAME, RESERVED_USER_DOC_TYPE, username)
                    .setDoc(User.Fields.ENABLED.getPreferredName(), enabled)
                    .setUpsert(User.Fields.PASSWORD.getPreferredName(), String.valueOf(ReservedRealm.DEFAULT_PASSWORD_HASH),
                            User.Fields.ENABLED.getPreferredName(), enabled)
                    .setRefreshPolicy(refreshPolicy)
                    .execute(new ActionListener<UpdateResponse>() {
                        @Override
                        public void onResponse(UpdateResponse updateResponse) {
                            assert updateResponse.getResult() == Result.UPDATED || updateResponse.getResult() == Result.CREATED;
                            clearRealmCache(username, listener, null);
                        }

                        @Override
                        public void onFailure(Exception e) {
                            listener.onFailure(e);
                        }
                    });
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    public void deleteUser(final DeleteUserRequest deleteUserRequest, final ActionListener<Boolean> listener) {
        if (state() != State.STARTED) {
            listener.onFailure(new IllegalStateException("user cannot be deleted as native user service has not been started"));
            return;
        }

        try {
            DeleteRequest request = client.prepareDelete(SecurityTemplateService.SECURITY_INDEX_NAME,
                    USER_DOC_TYPE, deleteUserRequest.username()).request();
            request.indicesOptions().ignoreUnavailable();
            request.setRefreshPolicy(deleteUserRequest.getRefreshPolicy());
            client.delete(request, new ActionListener<DeleteResponse>() {
                @Override
                public void onResponse(DeleteResponse deleteResponse) {
                    clearRealmCache(deleteUserRequest.username(), listener,
                            deleteResponse.getResult() == DocWriteResponse.Result.DELETED);
                }

                @Override
                public void onFailure(Exception e) {
                    listener.onFailure(e);
                }
            });
        } catch (Exception e) {
            logger.error("unable to remove user", e);
            listener.onFailure(e);
        }
    }

    public boolean canStart(ClusterState clusterState, boolean master) {
        if (state() != State.INITIALIZED) {
            return false;
        }

        if (clusterState.blocks().hasGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)) {
            // wait until the gateway has recovered from disk, otherwise we
            // think may not have the .security index but they it may not have
            // been restored from the cluster state on disk yet
            logger.debug("native users store waiting until gateway has recovered from disk");
            return false;
        }

        if (securityIndexMappingAndTemplateUpToDate(clusterState, logger) == false) {
            return false;
        }

        IndexMetaData metaData = clusterState.metaData().index(SecurityTemplateService.SECURITY_INDEX_NAME);
        if (metaData == null) {
            logger.debug("security index [{}] does not exist, so service can start", SecurityTemplateService.SECURITY_INDEX_NAME);
            return true;
        }

        if (clusterState.routingTable().index(SecurityTemplateService.SECURITY_INDEX_NAME).allPrimaryShardsActive()) {
            logger.debug("security index [{}] all primary shards started, so service can start",
                    SecurityTemplateService.SECURITY_INDEX_NAME);
            securityIndexExists = true;
            return true;
        }
        return false;
    }

    public void start() {
        try {
            if (state.compareAndSet(State.INITIALIZED, State.STARTING)) {
                this.scrollSize = SCROLL_SIZE_SETTING.get(settings);
                this.scrollKeepAlive = SCROLL_KEEP_ALIVE_SETTING.get(settings);
                state.set(State.STARTED);
            }
        } catch (Exception e) {
            logger.error("failed to start native user store", e);
            state.set(State.FAILED);
        }
    }

    public void stop() {
        if (state.compareAndSet(State.STARTED, State.STOPPING)) {
            state.set(State.STOPPED);
        }
    }

    /**
     * This method is used to verify the username and credentials against those stored in the system.
     *
     * @param username username to lookup the user by
     * @param password the plaintext password to verify
     * @return {@link} User object if successful or {@code null} if verification fails
     */
    User verifyPassword(String username, final SecuredString password) {
        if (state() != State.STARTED) {
            logger.trace("attempted to verify user credentials for [{}] but service was not started", username);
            return null;
        }

        UserAndPassword user = getUserAndPassword(username);
        if (user == null || user.passwordHash() == null) {
            return null;
        }
        if (hasher.verify(password, user.passwordHash())) {
            return user.user();
        }
        return null;
    }

    public boolean started() {
        return state() == State.STARTED;
    }

    boolean securityIndexExists() {
        return securityIndexExists;
    }

    ReservedUserInfo getReservedUserInfo(String username) throws Exception {
        assert started();
        final AtomicReference<ReservedUserInfo> userInfoRef = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        client.prepareGet(SecurityTemplateService.SECURITY_INDEX_NAME, RESERVED_USER_DOC_TYPE, username)
                .execute(new LatchedActionListener<>(new ActionListener<GetResponse>() {
                    @Override
                    public void onResponse(GetResponse getResponse) {
                        if (getResponse.isExists()) {
                            Map<String, Object> sourceMap = getResponse.getSourceAsMap();
                            String password = (String) sourceMap.get(User.Fields.PASSWORD.getPreferredName());
                            Boolean enabled = (Boolean) sourceMap.get(Fields.ENABLED.getPreferredName());
                            if (password == null || password.isEmpty()) {
                                failure.set(new IllegalStateException("password hash must not be empty!"));
                            } else if (enabled == null) {
                                failure.set(new IllegalStateException("enabled must not be null!"));
                            } else {
                                userInfoRef.set(new ReservedUserInfo(password.toCharArray(), enabled));
                            }
                        }
                    }

                    @Override
                    public void onFailure(Exception e) {
                        if (e instanceof IndexNotFoundException) {
                            logger.trace((Supplier<?>) () -> new ParameterizedMessage(
                                    "could not retrieve built in user [{}] info since security index does not exist", username), e);
                        } else {
                            logger.error(
                                    (Supplier<?>) () -> new ParameterizedMessage(
                                            "failed to retrieve built in user [{}] info", username), e);
                            failure.set(e);
                        }
                    }
                }, latch));

        try {
            final boolean responseReceived = latch.await(30, TimeUnit.SECONDS);
            if (responseReceived == false) {
                failure.set(new TimeoutException("timed out trying to get built in user [" + username + "]"));
            }
        } catch (InterruptedException e) {
            failure.set(e);
        }

        Exception failureCause = failure.get();
        if (failureCause != null) {
            // if there is any sort of failure we need to throw an exception to prevent the fallback to the default password...
            throw failureCause;
        }
        return userInfoRef.get();
    }

    Map<String, ReservedUserInfo> getAllReservedUserInfo() throws Exception {
        assert started();
        final Map<String, ReservedUserInfo> userInfos = new HashMap<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        client.prepareSearch(SecurityTemplateService.SECURITY_INDEX_NAME)
                .setTypes(RESERVED_USER_DOC_TYPE)
                .setQuery(QueryBuilders.matchAllQuery())
                .setFetchSource(true)
                .execute(new LatchedActionListener<>(new ActionListener<SearchResponse>() {
                    @Override
                    public void onResponse(SearchResponse searchResponse) {
                        assert searchResponse.getHits().getTotalHits() <= 10 : "there are more than 10 reserved users we need to change " +
                                "this to retrieve them all!";
                        for (SearchHit searchHit : searchResponse.getHits().getHits()) {
                            Map<String, Object> sourceMap = searchHit.getSource();
                            String password = (String) sourceMap.get(User.Fields.PASSWORD.getPreferredName());
                            Boolean enabled = (Boolean) sourceMap.get(Fields.ENABLED.getPreferredName());
                            if (password == null || password.isEmpty()) {
                                failure.set(new IllegalStateException("password hash must not be empty!"));
                                break;
                            } else if (enabled == null) {
                                failure.set(new IllegalStateException("enabled must not be null!"));
                                break;
                            } else {
                                userInfos.put(searchHit.getId(), new ReservedUserInfo(password.toCharArray(), enabled));
                            }
                        }
                    }

                    @Override
                    public void onFailure(Exception e) {
                        if (e instanceof IndexNotFoundException) {
                            logger.trace("could not retrieve built in users since security index does not exist", e);
                        } else {
                            logger.error("failed to retrieve built in users", e);
                            failure.set(e);
                        }
                    }
                }, latch));

        try {
            final boolean responseReceived = latch.await(30, TimeUnit.SECONDS);
            if (responseReceived == false) {
                failure.set(new TimeoutException("timed out trying to get built in users"));
            }
        } catch (InterruptedException e) {
            failure.set(e);
        }

        Exception failureCause = failure.get();
        if (failureCause != null) {
            // if there is any sort of failure we need to throw an exception to prevent the fallback to the default password...
            throw failureCause;
        }
        return userInfos;
    }

    private void clearScrollResponse(String scrollId) {
        ClearScrollRequest clearScrollRequest = client.prepareClearScroll().addScrollId(scrollId).request();
        client.clearScroll(clearScrollRequest, new ActionListener<ClearScrollResponse>() {
            @Override
            public void onResponse(ClearScrollResponse response) {
                // cool, it cleared, we don't really care though...
            }

            @Override
            public void onFailure(Exception t) {
                // Not really much to do here except for warn about it...
                logger.warn((Supplier<?>) () -> new ParameterizedMessage("failed to clear scroll [{}]", scrollId), t);
            }
        });
    }

    private <Response> void clearRealmCache(String username, ActionListener<Response> listener, Response response) {
        SecurityClient securityClient = new SecurityClient(client);
        ClearRealmCacheRequest request = securityClient.prepareClearRealmCache()
                .usernames(username).request();
        securityClient.clearRealmCache(request, new ActionListener<ClearRealmCacheResponse>() {
            @Override
            public void onResponse(ClearRealmCacheResponse nodes) {
                listener.onResponse(response);
            }

            @Override
            public void onFailure(Exception e) {
                logger.error((Supplier<?>) () -> new ParameterizedMessage("unable to clear realm cache for user [{}]", username), e);
                ElasticsearchException exception = new ElasticsearchException("clearing the cache for [" + username
                        + "] failed. please clear the realm cache manually", e);
                listener.onFailure(exception);
            }
        });
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        final boolean exists = event.state().metaData().indices().get(SecurityTemplateService.SECURITY_INDEX_NAME) != null;
        // make sure all the primaries are active
        if (exists && event.state().routingTable().index(SecurityTemplateService.SECURITY_INDEX_NAME).allPrimaryShardsActive()) {
            logger.debug("security index [{}] all primary shards started, so polling can start",
                    SecurityTemplateService.SECURITY_INDEX_NAME);
            securityIndexExists = true;
        } else {
            // always set the value - it may have changed...
            securityIndexExists = false;
        }
    }

    public State state() {
        return state.get();
    }

    // FIXME hack for testing
    public void reset() {
        final State state = state();
        if (state != State.STOPPED && state != State.FAILED) {
            throw new IllegalStateException("can only reset if stopped!!!");
        }
        this.securityIndexExists = false;
        this.state.set(State.INITIALIZED);
    }

    @Nullable
    private UserAndPassword transformUser(String username, Map<String, Object> sourceMap) {
        if (sourceMap == null) {
            return null;
        }
        try {
            String password = (String) sourceMap.get(User.Fields.PASSWORD.getPreferredName());
            String[] roles = ((List<String>) sourceMap.get(User.Fields.ROLES.getPreferredName())).toArray(Strings.EMPTY_ARRAY);
            String fullName = (String) sourceMap.get(User.Fields.FULL_NAME.getPreferredName());
            String email = (String) sourceMap.get(User.Fields.EMAIL.getPreferredName());
            Boolean enabled = (Boolean) sourceMap.get(User.Fields.ENABLED.getPreferredName());
            if (enabled == null) {
                // fallback mechanism as a user from 2.x may not have the enabled field
                enabled = Boolean.TRUE;
            }
            Map<String, Object> metadata = (Map<String, Object>) sourceMap.get(User.Fields.METADATA.getPreferredName());
            return new UserAndPassword(new User(username, roles, fullName, email, metadata, enabled), password.toCharArray());
        } catch (Exception e) {
            logger.error((Supplier<?>) () -> new ParameterizedMessage("error in the format of data for user [{}]", username), e);
            return null;
        }
    }

    private static boolean isIndexNotFoundOrDocumentMissing(Exception e) {
        if (e instanceof ElasticsearchException) {
            Throwable cause = ExceptionsHelper.unwrapCause(e);
            if (cause instanceof IndexNotFoundException || cause instanceof DocumentMissingException) {
                return true;
            }
        }
        return false;
    }

    static class ReservedUserInfo {

        final char[] passwordHash;
        final boolean enabled;

        ReservedUserInfo(char[] passwordHash, boolean enabled) {
            this.passwordHash = passwordHash;
            this.enabled = enabled;
        }
    }

    public static void addSettings(List<Setting<?>> settings) {
        settings.add(SCROLL_SIZE_SETTING);
        settings.add(SCROLL_KEEP_ALIVE_SETTING);
    }
}
