/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.security.authentication.token;

import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.jcr.AccessDeniedException;
import javax.jcr.Credentials;
import javax.jcr.RepositoryException;
import javax.jcr.SimpleCredentials;

import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.api.security.authentication.token.TokenCredentials;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.core.IdentifierManager;
import org.apache.jackrabbit.oak.namepath.NamePathMapper;
import org.apache.jackrabbit.oak.plugins.name.NamespaceConstants;
import org.apache.jackrabbit.oak.spi.security.ConfigurationParameters;
import org.apache.jackrabbit.oak.spi.security.authentication.ImpersonationCredentials;
import org.apache.jackrabbit.oak.spi.security.authentication.token.TokenInfo;
import org.apache.jackrabbit.oak.spi.security.authentication.token.TokenProvider;
import org.apache.jackrabbit.oak.spi.security.user.UserConfiguration;
import org.apache.jackrabbit.oak.spi.security.user.util.PasswordUtility;
import org.apache.jackrabbit.oak.util.NodeUtil;
import org.apache.jackrabbit.util.ISO8601;
import org.apache.jackrabbit.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.jackrabbit.oak.api.Type.STRING;
import static org.apache.jackrabbit.oak.core.IdentifierManager.getIdentifier;

/**
 * Default implementation of the {@code TokenProvider} interface that keeps login
 * tokens in the content repository. As a precondition the configured the user
 * management implementation must provide paths for all
 * {@link org.apache.jackrabbit.api.security.user.User users} that refer to
 * a valid {@link Tree} in the content repository.
 * <p/>
 * <h3>Backwards compatibility with Jackrabbit 2.x</h3>
 * For security reasons the nodes storing the token information now have a
 * dedicated node type (rep:Token) which has the following definition:
 * <pre>
 *     [rep:Token] > nt:unstructured, mix:referenceable
 *     - rep:token.key (STRING) protected mandatory
 *     - rep:token.exp (STRING) protected mandatory
 * </pre>
 * Consequently the hash of the token and the expiration time of tokens generated
 * by this provider can no longer be manipulated using regular JCR item
 * modifications.<p>
 * <p/>
 * Existing login tokens generated by Jackrabbit 2.x which are migrated to
 * OAK will still be valid (unless they expire) due to the fact that
 * {@link #getTokenInfo(String)} and the implementation of the {@link TokenInfo}
 * interface will not validate the node type of the token node associated with
 * a given token.
 */
public class TokenProviderImpl implements TokenProvider {

    /**
     * logger instance
     */
    private static final Logger log = LoggerFactory.getLogger(TokenProviderImpl.class);

    /**
     * Constant for the token attribute passed with valid simple credentials to
     * trigger the generation of a new token.
     */
    private static final String TOKEN_ATTRIBUTE = ".token";
    private static final String TOKEN_ATTRIBUTE_EXPIRY = "rep:token.exp";
    private static final String TOKEN_ATTRIBUTE_KEY = "rep:token.key";
    private static final String TOKENS_NODE_NAME = ".tokens";
    private static final String TOKENS_NT_NAME = JcrConstants.NT_UNSTRUCTURED;
    private static final String TOKEN_NT_NAME = "rep:Token";

    /**
     * Default expiration time in ms for login tokens is 2 hours.
     */
    private static final long DEFAULT_TOKEN_EXPIRATION = 2 * 3600 * 1000;
    private static final int DEFAULT_KEY_SIZE = 8;
    private static final char DELIM = '_';

    private static final Set<String> RESERVED_ATTRIBUTES = new HashSet(2);

    static {
        RESERVED_ATTRIBUTES.add(TOKEN_ATTRIBUTE);
        RESERVED_ATTRIBUTES.add(TOKEN_ATTRIBUTE_EXPIRY);
        RESERVED_ATTRIBUTES.add(TOKEN_ATTRIBUTE_KEY);
    }

    private final Root root;
    private final ConfigurationParameters options;

    private final long tokenExpiration;
    private final UserManager userManager;
    private final IdentifierManager identifierManager;

    public TokenProviderImpl(Root root, ConfigurationParameters options, UserConfiguration userConfiguration) {
        this.root = root;
        this.options = options;

        this.tokenExpiration = options.getConfigValue(PARAM_TOKEN_EXPIRATION, Long.valueOf(DEFAULT_TOKEN_EXPIRATION));
        this.userManager = userConfiguration.getUserManager(root, NamePathMapper.DEFAULT);
        this.identifierManager = new IdentifierManager(root);
    }

    //------------------------------------------------------< TokenProvider >---

    /**
     * Returns {@code true} if {@code SimpleCredentials} can be extracted from
     * the specified credentials object and that simple credentials object has
     * a {@link #TOKEN_ATTRIBUTE} attribute with an empty value.
     *
     * @param credentials The current credentials.
     * @return {@code true} if the specified credentials are {@link SimpleCredentials}
     *         or {@link ImpersonationCredentials} and if the (extracted) simple credentials
     *         object contain a {@link #TOKEN_ATTRIBUTE} attribute with an empty value;
     *         {@code false} otherwise.
     */
    @Override
    public boolean doCreateToken(Credentials credentials) {
        SimpleCredentials sc = extractSimpleCredentials(credentials);
        if (sc == null) {
            return false;
        } else {
            Object attr = sc.getAttribute(TOKEN_ATTRIBUTE);
            return (attr != null && "".equals(attr.toString()));
        }
    }

    /**
     * Create a separate token node underneath a dedicated token store within
     * the user home node. That token node contains the hashed token, the
     * expiration time and additional mandatory attributes that will be verified
     * during login.
     *
     * @param credentials The current credentials.
     * @return A new {@code TokenInfo} or {@code null} if the token could not
     *         be created.
     */
    @Override
    public TokenInfo createToken(Credentials credentials) {
        SimpleCredentials sc = extractSimpleCredentials(credentials);
        TokenInfo tokenInfo = null;
        if (sc != null) {
            String[] attrNames = sc.getAttributeNames();
            Map<String, String> attributes = new HashMap<String, String>(attrNames.length);
            for (String attrName : sc.getAttributeNames()) {
                attributes.put(attrName, sc.getAttribute(attrName).toString());
            }
            tokenInfo = createToken(sc.getUserID(), attributes);
            if (tokenInfo != null) {
                // also set the new token to the simple credentials.
                sc.setAttribute(TOKEN_ATTRIBUTE, tokenInfo.getToken());
            }
        }

        return tokenInfo;
    }

    /**
     * Create a separate token node underneath a dedicated token store within
     * the user home node. That token node contains the hashed token, the
     * expiration time and additional mandatory attributes that will be verified
     * during login.
     *
     * @param userId     The identifier of the user for which a new token should
     *                   be created.
     * @param attributes The attributes associated with the new token.
     * @return A new {@code TokenInfo} or {@code null} if the token could not
     *         be created.
     */
    @Override
    public TokenInfo createToken(String userId, Map<String, ?> attributes) {
        String error = "Failed to create login token. ";
        NodeUtil tokenParent = getTokenParent(userId);
        if (tokenParent != null) {
            try {
                long creationTime = new Date().getTime();
                Calendar creation = GregorianCalendar.getInstance();
                creation.setTimeInMillis(creationTime);
                String tokenName = Text.replace(ISO8601.format(creation), ":", ".");

                NodeUtil tokenNode = tokenParent.addChild(tokenName, TOKEN_NT_NAME);
                tokenNode.setString(JcrConstants.JCR_UUID, IdentifierManager.generateUUID());

                String key = generateKey(options.getConfigValue(PARAM_TOKEN_LENGTH, DEFAULT_KEY_SIZE));
                String nodeId = getIdentifier(tokenNode.getTree());
                String token = new StringBuilder(nodeId).append(DELIM).append(key).toString();

                String keyHash = PasswordUtility.buildPasswordHash(key);
                tokenNode.setString(TOKEN_ATTRIBUTE_KEY, keyHash);
                final long expirationTime = creationTime + tokenExpiration;
                tokenNode.setDate(TOKEN_ATTRIBUTE_EXPIRY, expirationTime);

                for (String name : attributes.keySet()) {
                    if (!RESERVED_ATTRIBUTES.contains(name)) {
                        String attr = attributes.get(name).toString();
                        tokenNode.setString(name, attr);
                    }
                }
                root.commit();
                return new TokenInfoImpl(tokenNode, token, userId);
            } catch (NoSuchAlgorithmException e) {
                // error while generating login token
                log.error(error, e.getMessage());
            } catch (UnsupportedEncodingException e) {
                // error while generating login token
                log.error(error, e.getMessage());
            } catch (CommitFailedException e) {
                // conflict while committing changes
                log.warn(error, e.getMessage());
            } catch (AccessDeniedException e) {
                log.warn(error, e.getMessage());
            }
        } else {
            log.warn("Unable to get/create token store for user " + userId);
        }
        return null;
    }

    /**
     * Retrieves the token information associated with the specified login
     * token. If no accessible {@code Tree} exists for the given token or if
     * the token is not associated with a valid user this method returns {@code null}.
     *
     * @param token A valid login token.
     * @return The {@code TokenInfo} associated with the specified token or
     *         {@code null} of the corresponding information does not exist or is not
     *         associated with a valid user.
     */
    @Override
    public TokenInfo getTokenInfo(String token) {
        int pos = token.indexOf(DELIM);
        String nodeId = (pos == -1) ? token : token.substring(0, pos);
        Tree tokenTree = identifierManager.getTree(nodeId);
        String userId = getUserId(tokenTree);
        if (tokenTree == null || !tokenTree.exists() || userId == null) {
            return null;
        } else {
            return new TokenInfoImpl(new NodeUtil(tokenTree), token, userId);
        }
    }

    @Override
    public boolean removeToken(TokenInfo tokenInfo) {
        Tree tokenTree = getTokenTree(tokenInfo);
        if (tokenTree != null && tokenTree.exists()) {
            try {
                if (tokenTree.remove()) {
                    root.commit();
                    return true;
                }
            } catch (CommitFailedException e) {
                log.debug("Error while removing expired token", e.getMessage());
            }
        }
        return false;
    }

    @Override
    public boolean resetTokenExpiration(TokenInfo tokenInfo, long loginTime) {
        Tree tokenTree = getTokenTree(tokenInfo);
        if (tokenTree != null && tokenTree.exists()) {
            NodeUtil tokenNode = new NodeUtil(tokenTree);
            long expTime = getExpirationTime(tokenNode, 0);
            if (tokenInfo.isExpired(loginTime)) {
                log.debug("Attempt to reset an expired token.");
                return false;
            }

            if (expTime - loginTime <= tokenExpiration / 2) {
                long expirationTime = loginTime + tokenExpiration;
                try {
                    tokenNode.setDate(TOKEN_ATTRIBUTE_EXPIRY, expirationTime);
                    root.commit();
                    log.debug("Successfully reset token expiration time.");
                    return true;
                } catch (CommitFailedException e) {
                    log.warn("Error while resetting token expiration", e.getMessage());
                }
            }
        }
        return false;
    }


    //--------------------------------------------------------------------------

    private static long getExpirationTime(NodeUtil tokenNode, long defaultValue) {
        return tokenNode.getLong(TOKEN_ATTRIBUTE_EXPIRY, defaultValue);
    }

    @CheckForNull
    private static SimpleCredentials extractSimpleCredentials(Credentials credentials) {
        if (credentials instanceof SimpleCredentials) {
            return (SimpleCredentials) credentials;
        }

        if (credentials instanceof ImpersonationCredentials) {
            Credentials base = ((ImpersonationCredentials) credentials).getBaseCredentials();
            if (base instanceof SimpleCredentials) {
                return (SimpleCredentials) base;
            }
        }

        // cannot extract SimpleCredentials
        return null;
    }

    @Nonnull
    private static String generateKey(int size) {
        SecureRandom random = new SecureRandom();
        byte key[] = new byte[size];
        random.nextBytes(key);

        StringBuilder res = new StringBuilder(key.length * 2);
        for (byte b : key) {
            res.append(Text.hexTable[(b >> 4) & 15]);
            res.append(Text.hexTable[b & 15]);
        }
        return res.toString();
    }

    @CheckForNull
    private Tree getTokenTree(TokenInfo tokenInfo) {
        if (tokenInfo instanceof TokenInfoImpl) {
            return root.getTree(((TokenInfoImpl) tokenInfo).tokenPath);
        } else {
            return null;
        }
    }

    @CheckForNull
    private String getUserId(Tree tokenTree) {
        if (tokenTree != null && tokenTree.exists()) {
            try {
                String userPath = Text.getRelativeParent(tokenTree.getPath(), 2);
                Authorizable authorizable = userManager.getAuthorizableByPath(userPath);
                if (authorizable != null && !authorizable.isGroup() && !((User) authorizable).isDisabled()) {
                    return authorizable.getID();
                }
            } catch (RepositoryException e) {
                log.debug("Cannot determine userID from token: ", e.getMessage());
            }
        }
        return null;
    }

    @CheckForNull
    private NodeUtil getTokenParent(String userId) {
        NodeUtil tokenParent = null;
        String parentPath = null;
        try {
            Authorizable user = userManager.getAuthorizable(userId);
            if (user != null && !user.isGroup()) {
                String userPath = user.getPath();
                NodeUtil userNode = new NodeUtil(root.getTree(userPath));
                tokenParent = userNode.getChild(TOKENS_NODE_NAME);
                if (tokenParent == null) {
                    tokenParent = userNode.addChild(TOKENS_NODE_NAME, TOKENS_NT_NAME);
                    parentPath = userPath + '/' + TOKENS_NODE_NAME;
                    root.commit();
                }
            } else {
                log.debug("Cannot create login token: No corresponding node for User " + userId + '.');
            }
        } catch (RepositoryException e) {
            // error while accessing user.
            log.debug("Error while accessing user " + userId + '.', e);
        } catch (CommitFailedException e) {
            // conflict while creating token store for this user -> refresh and
            // try to get the tree from the updated root.
            log.debug("Conflict while creating token store -> retrying", e.getMessage());
            root.refresh();
            if (parentPath != null) {
                Tree parentTree = root.getTree(parentPath);
                if (parentTree.exists()) {
                    tokenParent = new NodeUtil(parentTree);
                }
            }
        }
        return tokenParent;
    }

    //--------------------------------------------------------------------------

    /**
     * TokenInfo
     */
    private static final class TokenInfoImpl implements TokenInfo {

        private final String token;
        private final String tokenPath;
        private final String userId;

        private final long expirationTime;
        private final String key;

        private final Map<String, String> mandatoryAttributes;
        private final Map<String, String> publicAttributes;


        private TokenInfoImpl(NodeUtil tokenNode, String token, String userId) {
            this.token = token;
            this.tokenPath = tokenNode.getTree().getPath();
            this.userId = userId;

            expirationTime = getExpirationTime(tokenNode, Long.MIN_VALUE);
            key = tokenNode.getString(TOKEN_ATTRIBUTE_KEY, null);

            mandatoryAttributes = new HashMap<String, String>();
            publicAttributes = new HashMap<String, String>();
            for (PropertyState propertyState : tokenNode.getTree().getProperties()) {
                String name = propertyState.getName();
                String value = propertyState.getValue(STRING);
                if (RESERVED_ATTRIBUTES.contains(name)) {
                    continue;
                }
                if (isMandatoryAttribute(name)) {
                    mandatoryAttributes.put(name, value);
                } else if (isInfoAttribute(name)) {
                    // info attribute
                    publicAttributes.put(name, value);
                } // else: jcr specific property
            }
        }

        //------------------------------------------------------< TokenInfo >---

        @Override
        public String getUserId() {
            return userId;
        }

        @Override
        public String getToken() {
            return token;
        }

        @Override
        public boolean isExpired(long loginTime) {
            return expirationTime < loginTime;
        }

        @Override
        public boolean matches(TokenCredentials tokenCredentials) {
            String tk = tokenCredentials.getToken();
            int pos = tk.lastIndexOf(DELIM);
            if (pos > -1) {
                tk = tk.substring(pos + 1);
            }
            if (key == null || !PasswordUtility.isSame(key, tk)) {
                return false;
            }

            for (String name : mandatoryAttributes.keySet()) {
                String expectedValue = mandatoryAttributes.get(name);
                if (!expectedValue.equals(tokenCredentials.getAttribute(name))) {
                    return false;
                }
            }

            // update set of informative attributes on the credentials
            // based on the properties present on the token node.
            Collection<String> attrNames = Arrays.asList(tokenCredentials.getAttributeNames());
            for (String name : publicAttributes.keySet()) {
                if (!attrNames.contains(name)) {
                    tokenCredentials.setAttribute(name, publicAttributes.get(name).toString());

                }
            }
            return true;
        }

        @Override
        public Map<String, String> getPrivateAttributes() {
            return Collections.unmodifiableMap(mandatoryAttributes);
        }

        @Override
        public Map<String, String> getPublicAttributes() {
            return Collections.unmodifiableMap(publicAttributes);
        }

        /**
         * Returns {@code true} if the specified {@code attributeName}
         * starts with or equals {@link #TOKEN_ATTRIBUTE}.
         *
         * @param attributeName The attribute name.
         * @return {@code true} if the specified {@code attributeName}
         *         starts with or equals {@link #TOKEN_ATTRIBUTE}.
         */
        private static boolean isMandatoryAttribute(String attributeName) {
            return attributeName != null && attributeName.startsWith(TOKEN_ATTRIBUTE);
        }

        /**
         * Returns {@code false} if the specified attribute name doesn't have
         * a 'jcr' or 'rep' namespace prefix; {@code true} otherwise. This is
         * a lazy evaluation in order to avoid testing the defining node type of
         * the associated jcr property.
         *
         * @param attributeName The attribute name.
         * @return {@code true} if the specified property name doesn't seem
         *         to represent repository internal information.
         */
        private static boolean isInfoAttribute(String attributeName) {
            String prefix = Text.getNamespacePrefix(attributeName);
            return !NamespaceConstants.RESERVED_PREFIXES.contains(prefix);
        }
    }
}
