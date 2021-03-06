/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.identity.openidconnect;

import net.minidev.json.JSONObject;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.oauth2.internal.OAuth2ServiceComponentHolder;
import org.wso2.carbon.registry.api.RegistryException;
import org.wso2.carbon.registry.api.Resource;
import org.wso2.carbon.registry.core.service.RegistryService;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.apache.commons.collections.MapUtils.isEmpty;
import static org.wso2.carbon.identity.oauth.common.OAuthConstants.OIDCClaims.ADDRESS;
import static org.wso2.carbon.identity.oauth.common.OAuthConstants.OIDCClaims.EMAIL_VERIFIED;
import static org.wso2.carbon.identity.oauth.common.OAuthConstants.OIDCClaims.PHONE_NUMBER_VERIFIED;
import static org.wso2.carbon.identity.oauth.common.OAuthConstants.OIDCClaims.UPDATED_AT;
import static org.wso2.carbon.identity.oauth.common.OAuthConstants.SCOPE_RESOURCE_PATH;

/**
 * Utility to handle OIDC Claim related functionality.
 */
public class OIDCClaimUtil {

    private static final String ADDRESS_PREFIX = "address.";
    private static final String ADDRESS_SCOPE = "address";
    private static final String OIDC_SCOPE_CLAIM_SEPARATOR = ",";

    private static final Log log = LogFactory.getLog(OIDCClaimUtil.class);
    private static final String SCOPE_CLAIM_PREFIX = ".";

    private OIDCClaimUtil() {
    }

    /**
     * Filter user claims based on OIDC Scopes defined.
     * <p>
     * Each OIDC Scope defined has a set of permitted claims. First we consider the requested scopes and aggregate
     * the allowed claims for each requested scope if they are defined OIDC Scopes. Then we filter the user claims
     * which belong to the aggregated allowed claims.
     * </p>
     *
     * @param spTenantDomain  Tenant domain of the service provider to which the OAuth app belongs to.
     * @param requestedScopes Request scopes in the OIDC request.
     * @param userClaims      Retrieved claim values of the authenticated user.
     * @return Claim Map after filtering user claims based on defined scopes.
     */
    public static Map<String, Object> getClaimsFilteredByOIDCScopes(String spTenantDomain,
                                                                    String[] requestedScopes,
                                                                    Map<String, Object> userClaims) {
        if (isEmpty(userClaims)) {
            // No user claims to filter.
            if (log.isDebugEnabled()) {
                log.debug("No user claims to filter. Returning an empty map of filtered claims.");
            }
            return new HashMap<>();
        }

        Map<String, Object> claimsToBeReturned = new HashMap<>();
        Map<String, Object> addressScopeClaims = new HashMap<>();

        // Map<"openid", "first_name,last_name,username">
        Properties oidcScopeProperties = getOIDCScopeProperties(spTenantDomain);
        if (isNotEmpty(oidcScopeProperties)) {
            List<String> addressScopeClaimUris = getAddressScopeClaimUris(oidcScopeProperties);
            // Iterate through scopes requested in the OAuth2/OIDC request to filter claims
            for (String requestedScope : requestedScopes) {
                // Check if requested scope is a supported OIDC scope value
                if (oidcScopeProperties.containsKey(requestedScope)) {
                    if (log.isDebugEnabled()) {
                        log.debug("Requested scope: " + requestedScope + " is a defined OIDC Scope in tenantDomain: " +
                                spTenantDomain + ". Filtering claims based on the permitted claims in the scope.");
                    }
                    // Requested scope is an registered OIDC scope. Filter and return the claims belonging to the scope.
                    Map<String, Object> filteredClaims =
                            handleRequestedOIDCScope(userClaims, addressScopeClaims, oidcScopeProperties,
                                    addressScopeClaimUris, requestedScope);
                    claimsToBeReturned.putAll(filteredClaims);
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Requested scope: " + requestedScope + " is not a defined OIDC Scope in " +
                                "tenantDomain: " + spTenantDomain + ".");
                    }
                }
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("No OIDC scopes defined for tenantDomain: " + spTenantDomain + ". Cannot proceed with " +
                        "filtering user claims therefore returning an empty claim map.");
            }
        }

        // Some OIDC claims need special formatting etc. These are handled below.
        if (isNotEmpty(addressScopeClaims)) {
            handleAddressClaim(claimsToBeReturned, addressScopeClaims);
        }

        handleUpdateAtClaim(claimsToBeReturned);
        handlePhoneNumberVerifiedClaim(claimsToBeReturned);
        handleEmailVerifiedClaim(claimsToBeReturned);

        return claimsToBeReturned;
    }

    private static boolean isNotEmpty(Map<String, Object> claimsToBeReturned) {
        return claimsToBeReturned != null && !claimsToBeReturned.isEmpty();
    }

    private static Map<String, Object> handleRequestedOIDCScope(Map<String, Object> userClaimsInOIDCDialect,
                                                                Map<String, Object> addressScopeClaims,
                                                                Properties oidcScopeProperties,
                                                                List<String> addressScopeClaimUris,
                                                                String oidcScope) {
        Map<String, Object> filteredClaims = new HashMap<>();
        List<String> claimUrisInRequestedScope = getClaimUrisInSupportedOidcScope(oidcScopeProperties, oidcScope);
        for (String scopeClaim : claimUrisInRequestedScope) {
            String oidcClaimUri = getOIDCClaimUri(scopeClaim);
            // Check whether the user claims contain the permitted claim uri
            if (userClaimsInOIDCDialect.containsKey(oidcClaimUri)) {
                Object claimValue = userClaimsInOIDCDialect.get(oidcClaimUri);
                // User claim is allowed for this scope.
                if (isAddressClaim(scopeClaim, addressScopeClaimUris)) {
                    addressScopeClaims.put(oidcClaimUri, claimValue);
                } else {
                    filteredClaims.put(oidcClaimUri, claimValue);
                }
            }
        }
        return filteredClaims;
    }

    /**
     * There can be situations where we have added a scope prefix to identify special claims.
     * <p>
     * For example, claims belonging to address can be prefixed as address.country, address.street. But when
     * returning we need to remove the prefix.
     *
     * @param scopeClaim claim uri defined in the OIDC Scope
     * @return Scope prefix removed claim URI
     */
    private static String getOIDCClaimUri(String scopeClaim) {
        return StringUtils.contains(scopeClaim, SCOPE_CLAIM_PREFIX) ?
                StringUtils.substringAfterLast(scopeClaim, SCOPE_CLAIM_PREFIX) :
                StringUtils.substringBefore(scopeClaim, SCOPE_CLAIM_PREFIX);
    }

    private static boolean isNotEmpty(Properties properties) {
        return properties != null && !properties.isEmpty();
    }

    private static void handleAddressClaim(Map<String, Object> returnedClaims,
                                           Map<String, Object> claimsforAddressScope) {
        if (MapUtils.isNotEmpty(claimsforAddressScope)) {
            final JSONObject jsonObject = new JSONObject();
            for (Map.Entry<String, Object> addressScopeClaimEntry : claimsforAddressScope.entrySet()) {
                jsonObject.put(addressScopeClaimEntry.getKey(), addressScopeClaimEntry.getValue());
            }
            returnedClaims.put(ADDRESS, jsonObject);
        }
    }

    private static List<String> getAddressScopeClaimUris(Properties oidcProperties) {
        return getClaimUrisInSupportedOidcScope(oidcProperties, ADDRESS_SCOPE);
    }

    private static boolean isAddressClaim(String scopeClaim, List<String> addressScopeClaims) {
        return StringUtils.startsWith(scopeClaim, ADDRESS_PREFIX) || addressScopeClaims.contains(scopeClaim);
    }

    private static List<String> getClaimUrisInSupportedOidcScope(Properties properties, String requestedScope) {
        String[] requestedScopeClaimsArray;
        if (StringUtils.isBlank(properties.getProperty(requestedScope))) {
            requestedScopeClaimsArray = new String[0];
        } else {
            requestedScopeClaimsArray = properties.getProperty(requestedScope).split(OIDC_SCOPE_CLAIM_SEPARATOR);
        }
        return Arrays.asList(requestedScopeClaimsArray);
    }

    private static void handleUpdateAtClaim(Map<String, Object> returnClaims) {
        if (returnClaims.containsKey(UPDATED_AT) && returnClaims.get(UPDATED_AT) != null) {
            if (returnClaims.get(UPDATED_AT) instanceof String) {
                returnClaims.put(UPDATED_AT, Long.parseLong((String) (returnClaims.get(UPDATED_AT))));
            }
        }
    }

    private static void handlePhoneNumberVerifiedClaim(Map<String, Object> returnClaims) {
        if (returnClaims.containsKey(PHONE_NUMBER_VERIFIED))
            if (returnClaims.get(PHONE_NUMBER_VERIFIED) != null) {
                if (returnClaims.get(PHONE_NUMBER_VERIFIED) instanceof String) {
                    returnClaims.put(PHONE_NUMBER_VERIFIED, (Boolean.valueOf((String)
                            (returnClaims.get(PHONE_NUMBER_VERIFIED)))));
                }
            }
    }

    private static void handleEmailVerifiedClaim(Map<String, Object> returnClaims) {
        if (returnClaims.containsKey(EMAIL_VERIFIED) && returnClaims.get(EMAIL_VERIFIED) != null) {
            if (returnClaims.get(EMAIL_VERIFIED) instanceof String) {
                returnClaims.put(EMAIL_VERIFIED, (Boolean.valueOf((String) (returnClaims.get(EMAIL_VERIFIED)))));
            }
        }
    }

    private static Properties getOIDCScopeProperties(String spTenantDomain) {
        Resource oidcScopesResource = null;
        try {
            int tenantId = IdentityTenantUtil.getTenantId(spTenantDomain);
            startTenantFlow(spTenantDomain, tenantId);

            RegistryService registryService = OAuth2ServiceComponentHolder.getRegistryService();
            if (registryService == null) {
                throw new RegistryException("Registry Service not set in OAuth2 Component. Component may not have " +
                        "initialized correctly.");
            }

            oidcScopesResource = registryService.getConfigSystemRegistry(tenantId).get(SCOPE_RESOURCE_PATH);
        } catch (RegistryException e) {
            log.error("Error while obtaining registry collection from registry path:" + SCOPE_RESOURCE_PATH, e);
        } finally {
            PrivilegedCarbonContext.endTenantFlow();
        }

        Properties propertiesToReturn = new Properties();
        if (oidcScopesResource != null) {
            for (Object scopeProperty : oidcScopesResource.getProperties().keySet()) {
                String propertyKey = (String) scopeProperty;
                propertiesToReturn.setProperty(propertyKey, oidcScopesResource.getProperty(propertyKey));
            }
        } else {
            log.error("OIDC scope resource cannot be found at " + SCOPE_RESOURCE_PATH + " for tenantDomain: "
                    + spTenantDomain);
        }
        return propertiesToReturn;
    }

    private static void startTenantFlow(String tenantDomain, int tenantId) {
        PrivilegedCarbonContext.startTenantFlow();
        PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
        carbonContext.setTenantId(tenantId);
        carbonContext.setTenantDomain(tenantDomain);
    }
}
