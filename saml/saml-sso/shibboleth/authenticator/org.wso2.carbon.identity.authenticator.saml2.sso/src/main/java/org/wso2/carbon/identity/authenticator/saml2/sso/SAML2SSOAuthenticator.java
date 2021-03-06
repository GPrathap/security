/*
*  Copyright (c) 2005-2010, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.carbon.identity.authenticator.saml2.sso;

import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.opensaml.saml2.core.Assertion;
import org.opensaml.saml2.core.Response;
import org.opensaml.xml.signature.SignatureValidator;
import org.opensaml.xml.signature.X509Data;
import org.opensaml.xml.validation.ValidationException;
import org.osgi.framework.BundleContext;
import org.osgi.util.tracker.ServiceTracker;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.core.AbstractAdmin;
import org.wso2.carbon.core.security.AuthenticatorsConfiguration;
import org.wso2.carbon.core.services.authentication.CarbonServerAuthenticator;
import org.wso2.carbon.core.services.util.CarbonAuthenticationUtil;
import org.wso2.carbon.core.util.AnonymousSessionUtil;
import org.wso2.carbon.core.util.PermissionUpdateUtil;
import org.wso2.carbon.identity.authenticator.saml2.sso.dto.AuthnReqDTO;
import org.wso2.carbon.identity.authenticator.saml2.sso.internal.SAML2SSOAuthBEDataHolder;
import org.wso2.carbon.identity.authenticator.saml2.sso.util.Util;
import org.wso2.carbon.identity.authenticator.saml2.sso.util.X509CredentialImpl;
import org.wso2.carbon.registry.core.service.RegistryService;
import org.wso2.carbon.user.core.UserRealm;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.AuthenticationObserver;
import org.wso2.carbon.utils.ServerConstants;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

public class SAML2SSOAuthenticator extends AbstractAdmin implements CarbonServerAuthenticator {

    private static final int DEFAULT_PRIORITY_LEVEL = 3;
    private static final String AUTHENTICATOR_NAME = "SAML2SSOAuthenticator";

    public static final Log log = LogFactory.getLog(SAML2SSOAuthenticator.class);

    public boolean login(AuthnReqDTO authDto) {
        HttpSession httpSession = getHttpSession();
        try {
            Response response = (Response) Util.unmarshall(Util.decode(authDto.getResponse()));
            String username = getUsernameFromResponse(response);

            if ((username == null) || username.trim().equals("")) {
                log.info("Authentication Request is rejected. " +
                         "SAMLResponse does not contain the username of the subject.");
                CarbonAuthenticationUtil.onFailedAdminLogin(httpSession, username, -1,
                                                            "SAML2 SSO Authentication", "Data");
                // Unable to call #handleAuthenticationCompleted since there is no way to determine
                // tenantId without knowing the username.
                return false;
            }

            RegistryService registryService = SAML2SSOAuthBEDataHolder.getInstance().getRegistryService();
            RealmService realmService = SAML2SSOAuthBEDataHolder.getInstance().getRealmService();
            String tenantDomain = UserCoreUtil.getTenantDomain(realmService, username);
            int tenantId = realmService.getTenantManager().getTenantId(tenantDomain);
            handleAuthenticationStarted(tenantId);
            boolean isSignatureValid = validateSignature(response, tenantDomain);
            if(!isSignatureValid){
                CarbonAuthenticationUtil.onFailedAdminLogin(httpSession, username, tenantId,
                        "SAML2 SSO Authentication", "Invalid Signature");
                handleAuthenticationCompleted(tenantId, false);
                return false;
            }

            username = UserCoreUtil.getTenantLessUsername(username);
            UserRealm realm = AnonymousSessionUtil.getRealmByTenantDomain(registryService,
                                                                          realmService, tenantDomain);

            PermissionUpdateUtil.updatePermissionTree(tenantId);
            boolean isAuthorized = realm.getAuthorizationManager().isUserAuthorized(username,
                                                                                    "/permission/admin/login", CarbonConstants.UI_PERMISSION_ACTION);
            if (isAuthorized) {
                CarbonAuthenticationUtil.onSuccessAdminLogin(httpSession, username,
                                                             tenantId, tenantDomain, "SAML2 SSO Authentication");
                handleAuthenticationCompleted(tenantId, true);
                return true;
            } else {
                log.info("Authentication Request is rejected. Authorization Failure.");
                CarbonAuthenticationUtil.onFailedAdminLogin(httpSession, username, tenantId,
                                                            "SAML2 SSO Authentication", "Invalid credential");
                handleAuthenticationCompleted(tenantId, false);
                return false;
            }
        } catch (Exception e) {
            String msg = "System error while Authenticating/Authorizing User : " + e.getMessage();
            log.error(msg, e);
            return false;
        }
    }

    private void handleAuthenticationStarted(int tenantId) {
        BundleContext bundleContext = SAML2SSOAuthBEDataHolder.getInstance().getBundleContext();
        if (bundleContext != null) {
            ServiceTracker tracker =
                    new ServiceTracker(bundleContext,
                            AuthenticationObserver.class.getName(), null);
            tracker.open();
            Object[] services = tracker.getServices();
            if (services != null) {
                for (Object service : services) {
                    ((AuthenticationObserver) service).startedAuthentication(tenantId);
                }
            }
            tracker.close();
        }
    }

    private void handleAuthenticationCompleted(int tenantId, boolean isSuccessful) {
        BundleContext bundleContext = SAML2SSOAuthBEDataHolder.getInstance().getBundleContext();
        if (bundleContext != null) {
            ServiceTracker tracker =
                    new ServiceTracker(bundleContext,
                            AuthenticationObserver.class.getName(), null);
            tracker.open();
            Object[] services = tracker.getServices();
            if (services != null) {
                for (Object service : services) {
                    ((AuthenticationObserver) service).completedAuthentication(
                            tenantId, isSuccessful);
                }
            }
            tracker.close();
        }
    }

    public void logout() {
        String loggedInUser;
        String delegatedBy;
        Date currentTime = Calendar.getInstance().getTime();
        SimpleDateFormat date = new SimpleDateFormat("'['yyyy-MM-dd HH:mm:ss,SSSS']'");
        HttpSession session = getHttpSession();

        if (session != null) {
            loggedInUser = (String) session.getAttribute(ServerConstants.USER_LOGGED_IN);
            delegatedBy = (String) session.getAttribute("DELEGATED_BY");
            if (delegatedBy == null) {
                log.info("'" + loggedInUser + "' logged out at " + date.format(currentTime));
            } else {
                log.info("'" + loggedInUser + "' logged out at " + date.format(currentTime)
                         + " delegated by " + delegatedBy);
            }
            session.invalidate();
        }
    }

    public boolean isHandle(MessageContext messageContext) {
        return true;
    }

    public boolean isAuthenticated(MessageContext messageContext) {
        HttpServletRequest request = (HttpServletRequest) messageContext
                .getProperty(HTTPConstants.MC_HTTP_SERVLETREQUEST);
        HttpSession httpSession = request.getSession();
        String loginStatus = (String) httpSession.getAttribute(ServerConstants.USER_LOGGED_IN);

        return (loginStatus != null);
    }

    public boolean authenticateWithRememberMe(MessageContext messageContext) {
        return false;
    }

    public int getPriority() {
        AuthenticatorsConfiguration authenticatorsConfiguration = AuthenticatorsConfiguration.getInstance();
        AuthenticatorsConfiguration.AuthenticatorConfig authenticatorConfig =
                authenticatorsConfiguration.getAuthenticatorConfig(AUTHENTICATOR_NAME);
        if (authenticatorConfig != null && authenticatorConfig.getPriority() > 0) {
            return authenticatorConfig.getPriority();
        }
        return DEFAULT_PRIORITY_LEVEL;
    }

    public String getAuthenticatorName() {
        return AUTHENTICATOR_NAME;
    }

    public boolean isDisabled() {
        AuthenticatorsConfiguration authenticatorsConfiguration = AuthenticatorsConfiguration.getInstance();
        AuthenticatorsConfiguration.AuthenticatorConfig authenticatorConfig =
                authenticatorsConfiguration.getAuthenticatorConfig(AUTHENTICATOR_NAME);
        if (authenticatorConfig != null) {
            return authenticatorConfig.isDisabled();
        }
        return false;
    }

    /**
     * Validate the signature of a SAML2 Response
     *
     * @param response   SAML2 Response
     * @param domainName domain name of the subject
     * @return true, if signature is valid.
     */
    private boolean validateSignature(Response response, String domainName) {

        boolean isSignatureValid = false;

        if(response.getSignature() == null){
            log.warn("SAML Response is not signed. So authentication process will be terminated.");
        } else {
            try {
                List x509Data = response.getSignature().getKeyInfo().getX509Datas();
                // Pick the first <ds:X509Data/> element
                X509Data x509Cred = (X509Data) x509Data.get(0);
                // Get the <ds:X509Certificate/> elements
                List x509Certs = x509Cred.getX509Certificates();
                org.opensaml.xml.signature.X509Certificate cert = (org.opensaml.xml.signature.X509Certificate)
                        x509Certs.get(0);
                // Instantiate a java.security.cert.X509Certificate object out of the
                // base64 decoded byte[] of the certificate
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                java.security.cert.X509Certificate x509Certificate = (X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(org.opensaml.xml.util.Base64.decode(cert.getValue())));
                SignatureValidator validator = new SignatureValidator(new X509CredentialImpl(x509Certificate));
                validator.validate(response.getSignature());
                isSignatureValid = true;
            } catch (ValidationException e) {
                log.warn("Signature validation failed for a SAML2 Response from domain : " + domainName);
            } catch (CertificateException e) {
                log.warn("Certificate error when validating signature for a SAML2 Response " +
                                                                    "from domain : " + domainName);                
            }
        }
        return isSignatureValid;
    }

    /**
     * Get the username from the SAML2 Response
     *
     * @param response SAML2 Response
     * @return username
     */
    private String getUsernameFromResponse(Response response) {
        List<Assertion> assertions = response.getAssertions();
        Assertion assertion = null;
        if (assertions != null && assertions.size() > 0) {
            assertion = assertions.get(0);
            return assertion.getSubject().getNameID().getValue();
        }
        return null;
    }

}
