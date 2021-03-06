/*******************************************************************************
 *     Cloud Foundry
 *     Copyright (c) [2009-2016] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.mock.ldap;

import org.cloudfoundry.identity.uaa.audit.event.AbstractUaaEvent;
import org.cloudfoundry.identity.uaa.authentication.UaaAuthentication;
import org.cloudfoundry.identity.uaa.authentication.event.IdentityProviderAuthenticationFailureEvent;
import org.cloudfoundry.identity.uaa.authentication.event.IdentityProviderAuthenticationSuccessEvent;
import org.cloudfoundry.identity.uaa.authentication.manager.DynamicZoneAwareAuthenticationManager;
import org.cloudfoundry.identity.uaa.constants.OriginKeys;
import org.cloudfoundry.identity.uaa.mfa.GoogleMfaProviderConfig;
import org.cloudfoundry.identity.uaa.mfa.JdbcMfaProviderProvisioning;
import org.cloudfoundry.identity.uaa.mfa.MfaProvider;
import org.cloudfoundry.identity.uaa.mock.DefaultConfigurationTestSuite;
import org.cloudfoundry.identity.uaa.mock.util.ApacheDSHelper;
import org.cloudfoundry.identity.uaa.mock.util.MockMvcUtils;
import org.cloudfoundry.identity.uaa.mock.util.MockMvcUtils.ZoneScimInviteData;
import org.cloudfoundry.identity.uaa.provider.IdentityProvider;
import org.cloudfoundry.identity.uaa.provider.IdentityProviderValidationRequest;
import org.cloudfoundry.identity.uaa.provider.IdentityProviderValidationRequest.UsernamePasswordAuthentication;
import org.cloudfoundry.identity.uaa.provider.LdapIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.scim.ScimGroup;
import org.cloudfoundry.identity.uaa.scim.ScimGroupProvisioning;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.cloudfoundry.identity.uaa.scim.exception.ScimResourceAlreadyExistsException;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimGroupExternalMembershipManager;
import org.cloudfoundry.identity.uaa.user.UaaUser;
import org.cloudfoundry.identity.uaa.user.UaaUserDatabase;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.util.UaaStringUtils;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneSwitchingFilter;
import org.cloudfoundry.identity.uaa.zone.JdbcIdentityZoneProvisioning;
import org.cloudfoundry.identity.uaa.zone.MfaConfig;
import org.cloudfoundry.identity.uaa.zone.UserConfig;

import com.fasterxml.jackson.core.type.TypeReference;
import org.hamcrest.core.StringContains;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.ldap.server.ApacheDsSSLContainer;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.StringUtils;
import org.springframework.web.context.support.XmlWebApplicationContext;

import java.io.File;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.servlet.http.HttpSession;

import static org.cloudfoundry.identity.uaa.constants.OriginKeys.LDAP;
import static org.cloudfoundry.identity.uaa.mock.util.MockMvcUtils.CookieCsrfPostProcessor.cookieCsrf;
import static org.cloudfoundry.identity.uaa.mock.util.MockMvcUtils.performMfaRegistrationInZone;
import org.cloudfoundry.identity.uaa.mock.util.MockMvcUtils;
import static org.cloudfoundry.identity.uaa.provider.ldap.ProcessLdapProperties.NONE;
import static org.cloudfoundry.identity.uaa.provider.ldap.ProcessLdapProperties.SIMPLE;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpHeaders.HOST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_HTML_VALUE;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static java.util.Collections.EMPTY_LIST;

@RunWith(Parameterized.class)
public class LdapMockMvcTests  {


    public static final String JAVAX_NET_SSL_TRUST_STORE = "javax.net.ssl.trustStore";
    private static int ldapPortRotation = 0;
    private static String defaultTrustStore;

    private String host;
    private static XmlWebApplicationContext webApplicationContext;
    private static MockMvc mockMvc;
    private ApplicationListener<AbstractUaaEvent> listener;

    public XmlWebApplicationContext getWebApplicationContext() {
        return webApplicationContext;
    }

    public MockMvc getMockMvc() {
        return mockMvc;
    }


    @BeforeClass
    public static void trustOurCustomCA() {
        ClassLoader classLoader = LdapCertificateMockMvcTests.class.getClassLoader();
        File file = new File(classLoader.getResource("certs/truststore-containing-the-ldap-ca.jks").getFile());

        defaultTrustStore = System.getProperty(JAVAX_NET_SSL_TRUST_STORE);
        System.setProperty(JAVAX_NET_SSL_TRUST_STORE, file.getAbsolutePath());

    }

    @AfterClass
    public static void revertOurCustomCA() {
        if (defaultTrustStore != null) {
            System.setProperty(JAVAX_NET_SSL_TRUST_STORE, defaultTrustStore);
        } else {
            System.clearProperty(JAVAX_NET_SSL_TRUST_STORE);
        }
    }

    @Parameters(name = "{index}: auth[{0}]; group[{1}]; url[{2}]; tls[{3}]")
    public static List<Object[]> data() {
        return Arrays.asList(new Object[][]{
            {"ldap-simple-bind.xml", "ldap-groups-null.xml", "ldap://localhost:33389", NONE},
            //{"ldap-simple-bind.xml", "ldap-groups-as-scopes.xml", "ldap://localhost:33389", SIMPLE},
            //{"ldap-simple-bind.xml", "ldap-groups-map-to-scopes.xml", "ldap://localhost:33389", SIMPLE},
            //{"ldap-simple-bind.xml", "ldap-groups-map-to-scopes.xml", "ldaps://localhost:33636", NONE},
            //{"ldap-search-and-bind.xml", "ldap-groups-null.xml", "ldap://localhost:33389", SIMPLE},
            //{"ldap-search-and-bind.xml", "ldap-groups-as-scopes.xml", "ldap://localhost:33389", SIMPLE},
            {"ldap-search-and-bind.xml", "ldap-groups-map-to-scopes.xml", "ldap://localhost:33389", SIMPLE},
            //{"ldap-search-and-bind.xml", "ldap-groups-map-to-scopes.xml", "ldaps://localhost:33636", NONE},
            //{"ldap-search-and-compare.xml", "ldap-groups-null.xml", "ldap://localhost:33389", NONE},
            //{"ldap-search-and-compare.xml", "ldap-groups-as-scopes.xml", "ldap://localhost:33389", NONE},
            //{"ldap-search-and-compare.xml", "ldap-groups-map-to-scopes.xml", "ldap://localhost:33389", SIMPLE},
            {"ldap-search-and-compare.xml", "ldap-groups-as-scopes.xml", "ldaps://localhost:33636", NONE},
            //{"ldap-search-and-compare.xml", "ldap-groups-map-to-scopes.xml", "ldaps://localhost:33636", NONE}
        });
    }

    private static ApacheDsSSLContainer apacheDS;
    private static ApacheDsSSLContainer apacheDS2;

    @AfterClass
    public static void afterClass() throws Exception {
        DefaultConfigurationTestSuite.destroyMyContext();
        apacheDS.stop();
    }

    @BeforeClass
    public static void startApacheDS() throws Exception {
        apacheDS = ApacheDSHelper.start();
        webApplicationContext = DefaultConfigurationTestSuite.setUpContext();
        FilterChainProxy springSecurityFilterChain = webApplicationContext.getBean("springSecurityFilterChain", FilterChainProxy.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .addFilter(springSecurityFilterChain)
            .build();
    }

    private String ldapProfile;
    private String ldapGroup;
    private String ldapBaseUrl;
    private String tlsConfig;

    private String REDIRECT_URI = "http://invitation.redirect.test";
    private ZoneScimInviteData zone;
    private IdentityProvider<LdapIdentityProviderDefinition> provider;

    public LdapMockMvcTests(String ldapProfile, String ldapGroup, String baseUrl, String tlsConfig) {
        this.ldapGroup = ldapGroup;
        this.ldapProfile = ldapProfile;
        this.ldapBaseUrl = baseUrl;
        this.tlsConfig = tlsConfig;
    }

    @Before
    public void createTestZone() throws Exception {

        String userId = new RandomValueStringGenerator().generate().toLowerCase();
        zone = MockMvcUtils.createZoneForInvites(getMockMvc(), getWebApplicationContext(), userId, REDIRECT_URI);

        LdapIdentityProviderDefinition definition = new LdapIdentityProviderDefinition();
        definition.setLdapProfileFile("ldap/" + ldapProfile);
        definition.setLdapGroupFile("ldap/" + ldapGroup);
        definition.setMaxGroupSearchDepth(10);
        definition.setBaseUrl(ldapBaseUrl);
        definition.setBindUserDn("cn=admin,ou=Users,dc=test,dc=com");
        definition.setBindPassword("adminsecret");
        definition.setSkipSSLVerification(true);
        definition.setTlsConfiguration(tlsConfig);
        definition.setMailAttributeName("mail");
        definition.setReferral("ignore");

        provider = MockMvcUtils.createIdentityProvider(getMockMvc(),
                                                       zone.getZone(),
                                                       LDAP,
                                                       definition);


        host = zone.getZone().getIdentityZone().getSubdomain() + ".localhost";
        IdentityZoneHolder.clear();

        listener = (ApplicationListener<AbstractUaaEvent>) mock(ApplicationListener.class);
        getWebApplicationContext().addApplicationListener(listener);
    }

    @After
    public void tearDown() throws Exception {
        getMockMvc().perform(
            delete("/identity-zones/{id}", zone.getZone().getIdentityZone().getId())
                .header("Authorization", "Bearer " + zone.getDefaultZoneAdminToken())
                .accept(APPLICATION_JSON))
            .andExpect(status().isOk());
        MockMvcUtils.removeEventListener(getWebApplicationContext(), listener);
    }


    @Test
    @DirtiesContext
    public void acceptInvitation_for_ldap_user_whose_username_is_not_email() throws Exception {
        getWebApplicationContext().getBean(JdbcTemplate.class).update("delete from expiring_code_store");
        String email = "marissa2@test.com";
        getWebApplicationContext().getBean(JdbcTemplate.class).update("DELETE FROM users WHERE email=?", email);
        LdapIdentityProviderDefinition definition = provider.getConfig();
        definition.setEmailDomain(Arrays.asList("test.com"));
        updateLdapProvider();
        String redirectUri = "http://" + host;

        URL url = MockMvcUtils.inviteUser(
            getWebApplicationContext(),
            getMockMvc(),
            email,
            zone.getAdminToken(),
            zone.getZone().getIdentityZone().getSubdomain(),
            zone.getScimInviteClient().getClientId(),
            LDAP,
            redirectUri
        );


        String code = MockMvcUtils.extractInvitationCode(url.toString());

        String userInfoOrigin = getWebApplicationContext().getBean(JdbcTemplate.class).queryForObject("select origin from users where email=? and identity_zone_id=?", String.class, email, zone.getZone().getIdentityZone().getId());
        String userInfoId = getWebApplicationContext().getBean(JdbcTemplate.class).queryForObject("select id from users where email=? and identity_zone_id=?", String.class, email, zone.getZone().getIdentityZone().getId());
        assertEquals(LDAP, userInfoOrigin);


        ResultActions actions = getMockMvc().perform(get("/invitations/accept")
                                                         .param("code", code)
                                                         .accept(MediaType.TEXT_HTML)
                                                         .header(HOST, host)
        );
        MvcResult result = actions.andExpect(status().isOk())
            .andExpect(content().string(containsString("Link your account")))
            .andExpect(content().string(containsString("Email: " + email)))
            .andExpect(content().string(containsString("Sign in with enterprise credentials:")))
            .andExpect(content().string(containsString("username")))
            .andExpect(content().string(containsString("<input type=\"submit\" value=\"Sign in\" class=\"island-button\"/>")))
            .andReturn();

        code = getWebApplicationContext().getBean(JdbcTemplate.class).queryForObject("select code from expiring_code_store", String.class);

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        String expectRedirectToLogin = "/login?success=invite_accepted&form_redirect_uri="+ URLEncoder.encode(redirectUri);
        getMockMvc().perform(post("/invitations/accept_enterprise.do")
                                 .session(session)
                                 .param("enterprise_username", "marissa2")
                                 .param("enterprise_password", LDAP)
                                 .param("enterprise_email", "email")
                                 .param("code", code)
                                 .header(HOST, host)
                                 .with(cookieCsrf()))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl(expectRedirectToLogin))
                .andExpect(unauthenticated())
            .andReturn();

        getMockMvc().perform(
                get(expectRedirectToLogin)
                        .with(cookieCsrf())
                        .session(session)
                        .header(HOST, host)
        )
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("form_redirect_uri")))
            .andExpect(content().string(containsString(URLEncoder.encode(redirectUri))));


        getMockMvc().perform(
                post("/login.do")
                .with(cookieCsrf())
                .param("username", "marissa2")
                .param("password", LDAP)
                .session(session)
                .header(HOST, host)
                .param("form_redirect_uri", redirectUri)
        )
            .andExpect(authenticated())
            .andExpect(status().isFound())
            .andExpect(redirectedUrl(redirectUri));



        String newUserInfoId = getWebApplicationContext().getBean(JdbcTemplate.class).queryForObject("select id from users where email=? and identity_zone_id=?", String.class, email, zone.getZone().getIdentityZone().getId());
        String newUserInfoOrigin = getWebApplicationContext().getBean(JdbcTemplate.class).queryForObject("select origin from users where email=? and identity_zone_id=?", String.class, email, zone.getZone().getIdentityZone().getId());
        String newUserInfoUsername = getWebApplicationContext().getBean(JdbcTemplate.class).queryForObject("select username from users where email=? and identity_zone_id=?", String.class, email, zone.getZone().getIdentityZone().getId());
        assertEquals(LDAP, newUserInfoOrigin);
        assertEquals("marissa2", newUserInfoUsername);
        //ensure that a new user wasn't created
        assertEquals(userInfoId, newUserInfoId);


        //email mismatch
        getWebApplicationContext().getBean(JdbcTemplate.class).update("delete from expiring_code_store");
        email = "different@test.com";
        url = MockMvcUtils.inviteUser(getWebApplicationContext(), getMockMvc(), email, zone.getAdminToken(), zone.getZone().getIdentityZone().getSubdomain(), zone.getScimInviteClient().getClientId(), LDAP, REDIRECT_URI);
        code = MockMvcUtils.extractInvitationCode(url.toString());

        actions = getMockMvc().perform(get("/invitations/accept")
                                           .param("code", code)
                                           .accept(MediaType.TEXT_HTML)
                                           .header(HOST, host)
        );
        result = actions.andExpect(status().isOk())
            .andExpect(content().string(containsString("Email: " + email)))
            .andExpect(content().string(containsString("Sign in with enterprise credentials:")))
            .andExpect(content().string(containsString("username")))
            .andReturn();

        code = getWebApplicationContext().getBean(JdbcTemplate.class).queryForObject("select code from expiring_code_store", String.class);

        session = (MockHttpSession) result.getRequest().getSession(false);
        getMockMvc().perform(post("/invitations/accept_enterprise.do")
                                 .session(session)
                                 .param("enterprise_username", "marissa2")
                                 .param("enterprise_password", LDAP)
                                 .param("enterprise_email", "email")
                                 .param("code", code)
                                 .header(HOST, host)
                                 .with(cookieCsrf()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().string(containsString("The authenticated email does not match the invited email. Please log in using a different account.")))
            .andReturn();
        boolean userVerified = Boolean.parseBoolean(getWebApplicationContext().getBean(JdbcTemplate.class).queryForObject("select verified from users where email=? and identity_zone_id=?", String.class, email, zone.getZone().getIdentityZone().getId()));
        assertFalse(userVerified);
    }

    @Test
    public void test_external_groups_whitelist() throws Exception {
        Assume.assumeThat("ldap-groups-map-to-scopes.xml, ldap-groups-as-scopes.xml", StringContains.containsString(ldapGroup));
        AuthenticationManager manager = getWebApplicationContext().getBean(DynamicZoneAwareAuthenticationManager.class);
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken("marissa3", "ldap3");

        LdapIdentityProviderDefinition def = provider.getConfig();
        def.addWhiteListedGroup("admins");
        def.addWhiteListedGroup("thirdmarissa");
        provider.setConfig(def);
        updateLdapProvider();

        IdentityZoneHolder.set(zone.getZone().getIdentityZone());
        Authentication auth = manager.authenticate(token);
        assertNotNull(auth);
        assertTrue(auth instanceof UaaAuthentication);
        UaaAuthentication uaaAuth = (UaaAuthentication) auth;
        Set<String> externalGroups = uaaAuth.getExternalGroups();
        assertNotNull(externalGroups);
        assertEquals(2, externalGroups.size());
        assertThat(externalGroups, containsInAnyOrder("admins", "thirdmarissa"));

        //default whitelist
        def = provider.getConfig();
        def.setExternalGroupsWhitelist(EMPTY_LIST);
        provider.setConfig(def);
        updateLdapProvider();
        IdentityZoneHolder.set(zone.getZone().getIdentityZone());
        auth = manager.authenticate(token);
        assertNotNull(auth);
        assertTrue(auth instanceof UaaAuthentication);
        uaaAuth = (UaaAuthentication) auth;
        externalGroups = uaaAuth.getExternalGroups();
        assertNotNull(externalGroups);
        assertEquals(0, externalGroups.size());

        IdentityZoneHolder.clear();
    }

    @Test
    public void testCustomUserAttributes() throws Exception {
        Assume.assumeThat("ldap-groups-map-to-scopes.xml, ldap-groups-as-scopes.xml", StringContains.containsString(ldapGroup));

        final String MANAGER = "uaaManager";
        final String MANAGERS = "managers";
        final String DENVER_CO = "Denver,CO";
        final String COST_CENTER = "costCenter";
        final String COST_CENTERS = COST_CENTER + "s";
        final String JOHN_THE_SLOTH = "John the Sloth";
        final String KARI_THE_ANT_EATER = "Kari the Ant Eater";
        final String FIRST_NAME = "first_name";
        final String FAMILY_NAME = "family_name";
        final String PHONE_NUMBER = "phone_number";
        final String EMAIL_VERIFIED = "email_verified";


        Map<String, Object> attributeMappings = new HashMap<>();

        LdapIdentityProviderDefinition definition = provider.getConfig();

        attributeMappings.put("user.attribute." + MANAGERS, MANAGER);
        attributeMappings.put("user.attribute." + COST_CENTERS, COST_CENTER);


        //test to remap the user/person properties
        attributeMappings.put(FIRST_NAME, "sn");
        attributeMappings.put(PHONE_NUMBER, "givenname");
        attributeMappings.put(FAMILY_NAME, "telephonenumber");
        attributeMappings.put(EMAIL_VERIFIED, "emailVerified");

        definition.setAttributeMappings(attributeMappings);
        provider.setConfig(definition);
        updateLdapProvider();


        String username = "marissa9";
        String password = "ldap9";
        MvcResult result = performUiAuthentication(username, password, HttpStatus.FOUND, true);

        UaaAuthentication authentication = (UaaAuthentication) ((SecurityContext) result.getRequest().getSession().getAttribute(SPRING_SECURITY_CONTEXT_KEY)).getAuthentication();

        assertEquals("Expected two user attributes", 2, authentication.getUserAttributes().size());
        assertNotNull("Expected cost center attribute", authentication.getUserAttributes().get(COST_CENTERS));
        assertEquals(DENVER_CO, authentication.getUserAttributes().getFirst(COST_CENTERS));

        assertNotNull("Expected manager attribute", authentication.getUserAttributes().get(MANAGERS));
        assertEquals("Expected 2 manager attribute values", 2, authentication.getUserAttributes().get(MANAGERS).size());
        assertThat(authentication.getUserAttributes().get(MANAGERS), containsInAnyOrder(JOHN_THE_SLOTH, KARI_THE_ANT_EATER));

        assertEquals("8885550986", getFamilyName(username));
        assertEquals("Marissa", getPhoneNumber(username));
        assertEquals("Marissa9", getGivenName(username));
        assertTrue(getVerified(username));
    }

    @Test
    public void testLdapConfigurationBeforeSave() throws Exception {
        //we only need to test this once
        Assume.assumeThat("ldap-search-and-bind.xml", StringContains.containsString(ldapProfile));
        Assume.assumeThat("ldap-groups-map-to-scopes.xml", StringContains.containsString(ldapGroup));

        String identityAccessToken = MockMvcUtils.getClientOAuthAccessToken(getMockMvc(), "identity", "identitysecret", "");
        String adminAccessToken = MockMvcUtils.getClientOAuthAccessToken(getMockMvc(), "admin", "adminsecret", "");
        IdentityZone zone = MockMvcUtils.createZoneUsingWebRequest(getMockMvc(), identityAccessToken);
        String zoneAdminToken = MockMvcUtils.getZoneAdminToken(getMockMvc(), adminAccessToken, zone.getId());

        LdapIdentityProviderDefinition definition = LdapIdentityProviderDefinition.searchAndBindMapGroupToScopes(
            "ldap://localhost:33389",
            "cn=admin,ou=Users,dc=test,dc=com",
            "adminsecret",
            "dc=test,dc=com",
            "cn={0}",
            "ou=scopes,dc=test,dc=com",
            "member={0}",
            "mail",
            null,
            false,
            true,
            true,
            10,
            true
        );

        IdentityProvider provider = new IdentityProvider();
        provider.setOriginKey(LDAP);
        provider.setName("Test ldap provider");
        provider.setType(LDAP);
        provider.setConfig(definition);
        provider.setActive(true);
        provider.setIdentityZoneId(zone.getId());

        UsernamePasswordAuthentication token = new UsernamePasswordAuthentication("marissa2", LDAP);

        IdentityProviderValidationRequest request = new IdentityProviderValidationRequest(provider, token);
        System.out.println("request = \n" + JsonUtils.writeValueAsString(request));
        //Happy Day Scenario
        MockHttpServletRequestBuilder post = post("/identity-providers/test")
            .header("Accept", APPLICATION_JSON_VALUE)
            .header("Content-Type", APPLICATION_JSON_VALUE)
            .header("Authorization", "Bearer " + zoneAdminToken)
            .contentType(APPLICATION_JSON)
            .content(JsonUtils.writeValueAsString(request))
            .header(IdentityZoneSwitchingFilter.HEADER, zone.getId());

        MvcResult result = getMockMvc().perform(post)
            .andExpect(status().isOk())
            .andReturn();

        assertEquals("\"ok\"", result.getResponse().getContentAsString());

        //Correct configuration, invalid credentials
        token = new UsernamePasswordAuthentication("marissa2", "koala");
        request = new IdentityProviderValidationRequest(provider, token);
        post = post("/identity-providers/test")
            .header("Accept", APPLICATION_JSON_VALUE)
            .header("Content-Type", APPLICATION_JSON_VALUE)
            .header("Authorization", "Bearer " + zoneAdminToken)
            .contentType(APPLICATION_JSON)
            .content(JsonUtils.writeValueAsString(request))
            .header(IdentityZoneSwitchingFilter.HEADER, zone.getId());

        result = getMockMvc().perform(post)
            .andExpect(status().isExpectationFailed())
            .andReturn();
        assertEquals("\"bad credentials\"", result.getResponse().getContentAsString());

        //Insufficent scope
        post = post("/identity-providers/test")
            .header("Accept", APPLICATION_JSON_VALUE)
            .header("Content-Type", APPLICATION_JSON_VALUE)
            .header("Authorization", "Bearer " + identityAccessToken)
            .contentType(APPLICATION_JSON)
            .content(JsonUtils.writeValueAsString(request))
            .header(IdentityZoneSwitchingFilter.HEADER, zone.getId());

        result = getMockMvc().perform(post)
            .andExpect(status().isForbidden())
            .andReturn();


        //Invalid LDAP configuration - change the password of search user
        definition = LdapIdentityProviderDefinition.searchAndBindMapGroupToScopes(
            "ldap://localhost:33389",
            "cn=admin,ou=Users,dc=test,dc=com",
            "adminsecret23",
            "dc=test,dc=com",
            "cn={0}",
            "ou=scopes,dc=test,dc=com",
            "member={0}",
            "mail",
            null,
            false,
            true,
            true,
            10,
            true
        );
        provider.setConfig(definition);
        request = new IdentityProviderValidationRequest(provider, token);
        post = post("/identity-providers/test")
            .header("Accept", APPLICATION_JSON_VALUE)
            .header("Content-Type", APPLICATION_JSON_VALUE)
            .header("Authorization", "Bearer " + zoneAdminToken)
            .contentType(APPLICATION_JSON)
            .content(JsonUtils.writeValueAsString(request))
            .header(IdentityZoneSwitchingFilter.HEADER, zone.getId());

        result = getMockMvc().perform(post)
            .andExpect(status().isBadRequest())
            .andReturn();
        assertThat(result.getResponse().getContentAsString(), containsString("Caused by:"));

        //Invalid LDAP configuration - no ldap server
        definition = LdapIdentityProviderDefinition.searchAndBindMapGroupToScopes(
            "ldap://localhost:33388",
            "cn=admin,ou=Users,dc=test,dc=com",
            "adminsecret",
            "dc=test,dc=com",
            "cn={0}",
            "ou=scopes,dc=test,dc=com",
            "member={0}",
            "mail",
            null,
            false,
            true,
            true,
            10,
            true
        );
        provider.setConfig(definition);
        request = new IdentityProviderValidationRequest(provider, token);
        post = post("/identity-providers/test")
            .header("Accept", APPLICATION_JSON_VALUE)
            .header("Content-Type", APPLICATION_JSON_VALUE)
            .header("Authorization", "Bearer " + zoneAdminToken)
            .contentType(APPLICATION_JSON)
            .content(JsonUtils.writeValueAsString(request))
            .header(IdentityZoneSwitchingFilter.HEADER, zone.getId());

        result = getMockMvc().perform(post)
            .andExpect(status().isBadRequest())
            .andReturn();
        assertThat(result.getResponse().getContentAsString(), containsString("Caused by:"));

        //Invalid LDAP configuration - invalid search base
        definition = LdapIdentityProviderDefinition.searchAndBindMapGroupToScopes(
            "ldap://localhost:33389",
            "cn=admin,ou=Users,dc=test,dc=com",
            "adminsecret",
            ",,,,,dc=test,dc=com",
            "cn={0}",
            "ou=scopes,dc=test,dc=com",
            "member={0}",
            "mail",
            null,
            false,
            true,
            true,
            10,
            true
        );
        provider.setConfig(definition);
        request = new IdentityProviderValidationRequest(provider, token);
        post = post("/identity-providers/test")
            .header("Accept", APPLICATION_JSON_VALUE)
            .header("Content-Type", APPLICATION_JSON_VALUE)
            .header("Authorization", "Bearer " + zoneAdminToken)
            .contentType(APPLICATION_JSON)
            .content(JsonUtils.writeValueAsString(request))
            .header(IdentityZoneSwitchingFilter.HEADER, zone.getId());

        result = getMockMvc().perform(post)
            .andExpect(status().isBadRequest())
            .andReturn();
        assertThat(result.getResponse().getContentAsString(), containsString("Caused by:"));

        token = new UsernamePasswordAuthentication("marissa2", LDAP);

        //SSL self signed cert problems
        definition = LdapIdentityProviderDefinition.searchAndBindMapGroupToScopes(
            "ldaps://localhost:33636",
            "cn=admin,ou=Users,dc=test,dc=com",
            "adminsecret",
            "dc=test,dc=com",
            "cn={0}",
            "ou=scopes,dc=test,dc=com",
            "member={0}",
            "mail",
            null,
            false,
            true,
            true,
            10,
            false
        );
        provider.setConfig(definition);
        request = new IdentityProviderValidationRequest(provider, token);
        post = post("/identity-providers/test")
            .header("Accept", APPLICATION_JSON_VALUE)
            .header("Content-Type", APPLICATION_JSON_VALUE)
            .header("Authorization", "Bearer " + zoneAdminToken)
            .contentType(APPLICATION_JSON)
            .content(JsonUtils.writeValueAsString(request))
            .header(IdentityZoneSwitchingFilter.HEADER, zone.getId());
        result = getMockMvc().perform(post)
            .andExpect(status().isBadRequest())
            .andReturn();
        assertThat(result.getResponse().getContentAsString(), containsString("Caused by:"));
        definition.setSkipSSLVerification(true);
        provider.setConfig(definition);
        request = new IdentityProviderValidationRequest(provider, token);
        post = post("/identity-providers/test")
            .header("Accept", APPLICATION_JSON_VALUE)
            .header("Content-Type", APPLICATION_JSON_VALUE)
            .header("Authorization", "Bearer " + zoneAdminToken)
            .contentType(APPLICATION_JSON)
            .content(JsonUtils.writeValueAsString(request))
            .header(IdentityZoneSwitchingFilter.HEADER, zone.getId());

        result = getMockMvc().perform(post)
            .andExpect(status().isOk())
            .andReturn();
        assertThat(result.getResponse().getContentAsString(), containsString("\"ok\""));
    }

    @Test
    public void testLoginInNonDefaultZone() throws Exception {
        assumeFalse(!(ldapProfile.contains("ldap-search-and-bind.xml") &&
            ldapGroup.contains("ldap-groups-map-to-scopes.xml")));


        getMockMvc().perform(get("/login")
                                 .header(HOST, host))
            .andExpect(status().isOk())
            .andExpect(view().name("login"))
            .andExpect(model().attributeDoesNotExist("saml"));


        getMockMvc().perform(post("/login.do").accept(TEXT_HTML_VALUE)
                                 .with(cookieCsrf())
                                 .header(HOST, host)
                                 .param("username", "marissa2")
                                 .param("password", LDAP))
            .andExpect(status().isFound())
            .andExpect(authenticated())
            .andExpect(redirectedUrl("/"));

        IdentityZoneHolder.set(zone.getZone().getIdentityZone());
        UaaUser user = getWebApplicationContext().getBean(UaaUserDatabase.class).retrieveUserByName("marissa2", LDAP);
        IdentityZoneHolder.clear();
        assertNotNull(user);
        assertEquals(LDAP, user.getOrigin());
        assertEquals(zone.getZone().getIdentityZone().getId(), user.getZoneId());

        provider.setActive(false);
        MockMvcUtils.createIdpUsingWebRequest(getMockMvc(), zone.getZone().getIdentityZone().getId(), zone.getZone().getZoneAdminToken(), provider, status().isOk(), true);

        getMockMvc().perform(post("/login.do").accept(TEXT_HTML_VALUE)
                                 .with(cookieCsrf())
                                 .header(HOST, host)
                                 .param("username", "marissa2")
                                 .param("password", LDAP))
            .andExpect(status().isFound())
            .andExpect(unauthenticated())
            .andExpect(redirectedUrl("/login?error=login_failure"));


        provider.setActive(true);
        MockMvcUtils.createIdpUsingWebRequest(getMockMvc(), zone.getZone().getIdentityZone().getId(), zone.getZone().getZoneAdminToken(), provider, status().isOk(), true);

        getMockMvc().perform(post("/login.do").accept(TEXT_HTML_VALUE)
                                 .with(cookieCsrf())
                                 .header(HOST, host)
                                 .param("username", "marissa2")
                                 .param("password", LDAP))
            .andExpect(status().isFound())
            .andExpect(authenticated())
            .andExpect(redirectedUrl("/"));

        IdentityZoneHolder.set(zone.getZone().getIdentityZone());
        user = getWebApplicationContext().getBean(UaaUserDatabase.class).retrieveUserByName("marissa2", LDAP);
        IdentityZoneHolder.clear();
        assertNotNull(user);
        assertEquals(LDAP, user.getOrigin());
        assertEquals(zone.getZone().getIdentityZone().getId(), user.getZoneId());
        assertEquals("marissa2@test.com", user.getEmail());
    }

    @Test
    public void testLogin_partial_result_exception_on_group_search() throws Exception {
        getMockMvc().perform(post("/login.do").accept(TEXT_HTML_VALUE)
                                 .with(cookieCsrf())
                                 .header(HOST, host)
                                 .param("username", "marissa8")
                                 .param("password", "ldap8"))
            .andExpect(status().isFound())
            .andExpect(authenticated())
            .andExpect(redirectedUrl("/"));

        IdentityZoneHolder.set(zone.getZone().getIdentityZone());
        UaaUser user = getWebApplicationContext().getBean(UaaUserDatabase.class).retrieveUserByName("marissa8", LDAP);
        IdentityZoneHolder.clear();
        assertNotNull(user);
        assertEquals(LDAP, user.getOrigin());
        assertEquals(zone.getZone().getIdentityZone().getId(), user.getZoneId());
    }

    @Test
    public void test_memberOf_search() throws Exception {
        Assume.assumeThat("ldap-groups-map-to-scopes.xml", StringContains.containsString(ldapGroup));
        transferDefaultMappingsToZone(zone.getZone().getIdentityZone());
        provider.getConfig().setGroupSearchBase("memberOf");
        updateLdapProvider();

        Object securityContext = getMockMvc().perform(post("/login.do").accept(TEXT_HTML_VALUE)
                                                          .with(cookieCsrf())
                                                          .header(HOST, host)
                                                          .param("username", "marissa10")
                                                          .param("password", "ldap10"))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/"))
            .andReturn().getRequest().getSession().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);

        assertNotNull(securityContext);
        assertTrue(securityContext instanceof SecurityContext);
        String[] list = new String[]{
            "internal.read",
            "internal.everything",
            "internal.superuser"
        };
        Authentication authentication = ((SecurityContext) securityContext).getAuthentication();
        validateUserAuthorities(list, authentication);
        IdentityZoneHolder.set(zone.getZone().getIdentityZone());
        UaaUser user = getWebApplicationContext().getBean(UaaUserDatabase.class).retrieveUserByName("marissa10", LDAP);
        IdentityZoneHolder.clear();
        assertNotNull(user);
        assertEquals(LDAP, user.getOrigin());
        assertEquals(zone.getZone().getIdentityZone().getId(), user.getZoneId());
    }


    public ClassPathXmlApplicationContext getBeanContext() throws Exception {
        DynamicZoneAwareAuthenticationManager zm = getWebApplicationContext().getBean(DynamicZoneAwareAuthenticationManager.class);
        zm.getLdapAuthenticationManager(zone.getZone().getIdentityZone(), provider).getLdapAuthenticationManager();
        return zm.getLdapAuthenticationManager(zone.getZone().getIdentityZone(), provider).getContext();
    }

    public Object getBean(String name) throws Exception {
        ClassPathXmlApplicationContext beanContext = getBeanContext();
        return beanContext.getBean(name);
    }

    public <T> T getBean(Class<T> clazz) throws Exception {
        return getBeanContext().getBean(clazz);
    }


    @Test
    public void printProfileType() throws Exception {
        assertEquals(ldapProfile, getBean("testLdapProfile"));
        assertEquals(ldapGroup, getBean("testLdapGroup"));
    }

    @Test
    public void test_read_and_write_config_then_login() throws Exception {

        String response = getMockMvc().perform(
            get("/identity-providers/"+provider.getId())
                .header(ACCEPT, APPLICATION_JSON)
                .header(HOST, host)
                .header(AUTHORIZATION, "Bearer " + zone.getAdminToken())
        )
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(response, not(containsString("bindPassword")));
        IdentityProvider<LdapIdentityProviderDefinition> provider = JsonUtils.readValue(response, new TypeReference<IdentityProvider<LdapIdentityProviderDefinition>>() {});
        assertNull(provider.getConfig().getBindPassword());

        getMockMvc().perform(
            put("/identity-providers/"+provider.getId())
                .content(JsonUtils.writeValueAsString(provider))
                .header(CONTENT_TYPE, APPLICATION_JSON)
                .header(ACCEPT, APPLICATION_JSON)
                .header(HOST, host)
                .header(AUTHORIZATION, "Bearer " + zone.getAdminToken())
        )
            .andExpect(status().isOk());

        testSuccessfulLogin();

    }

    @Test
    public void testLogin() throws Exception {
        getMockMvc().perform(
            get("/login")
                .header(HOST, host))
            .andExpect(status().isOk())
            .andExpect(view().name("login"))
            .andExpect(model().attributeDoesNotExist("saml"));

        getMockMvc().perform(
            post("/login.do").accept(TEXT_HTML_VALUE)
                .header(HOST, host)
                .with(cookieCsrf())
                .param("username", "marissa")
                .param("password", "koaladsada"))
            .andExpect(status().isFound())
            .andExpect(unauthenticated())
            .andExpect(redirectedUrl("/login?error=login_failure"));

        ArgumentCaptor<AbstractUaaEvent> captor = ArgumentCaptor.forClass(AbstractUaaEvent.class);
        verify(listener, atLeast(5)).onApplicationEvent(captor.capture());
        List<AbstractUaaEvent> allValues = captor.getAllValues();
        assertThat(allValues.get(4), instanceOf(IdentityProviderAuthenticationFailureEvent.class));
        IdentityProviderAuthenticationFailureEvent event = (IdentityProviderAuthenticationFailureEvent)allValues.get(4);
        assertEquals("marissa", event.getUsername());
        assertEquals(OriginKeys.LDAP, event.getAuthenticationType());

        testSuccessfulLogin();

        captor = ArgumentCaptor.forClass(AbstractUaaEvent.class);
        verify(listener, atLeast(5)).onApplicationEvent(captor.capture());
        allValues = captor.getAllValues();
        assertThat(allValues.get(12), instanceOf(IdentityProviderAuthenticationSuccessEvent.class));
        IdentityProviderAuthenticationSuccessEvent successEvent = (IdentityProviderAuthenticationSuccessEvent)allValues.get(12);
        assertEquals(OriginKeys.LDAP, successEvent.getAuthenticationType());
    }

    @Test
    public void testTwoLdapServers() throws Exception {
        int port = 33389 + 400 + (ldapPortRotation++);
        int sslPort = 33636 + 400 + (ldapPortRotation++);
        apacheDS2 = ApacheDSHelper.start(port,sslPort);
        String originalUrl = ldapBaseUrl;
        if (ldapBaseUrl.contains("ldap://")) {
            ldapBaseUrl = ldapBaseUrl + " ldap://localhost:"+port;
        } else {
            ldapBaseUrl = ldapBaseUrl + " ldaps://localhost:"+sslPort;
        }
        provider.getConfig().setBaseUrl(ldapBaseUrl);
        updateLdapProvider();
        try {

            testSuccessfulLogin();
            apacheDS.stop();
            testSuccessfulLogin();
            apacheDS2.stop();
        } finally {
            ldapBaseUrl = originalUrl;
            if (apacheDS.isRunning()) {
                apacheDS.stop();
            }
            apacheDS = null;
            if (apacheDS2.isRunning()) {
                apacheDS2.stop();
            }
            apacheDS2 = null;
            Thread.sleep(1500);
            apacheDS = ApacheDSHelper.start();
        }
    }

    @Test
    public void test_username_with_space() throws Exception {
        getMockMvc().perform(
            post("/login.do").accept(TEXT_HTML_VALUE)
                .header(HOST, host)
                .with(cookieCsrf())
                .param("username", "marissa 11")
                .param("password", "ldap11"))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/"));

    }

    @Test
    public void testLdapAuthenticationWithMfa() throws Exception {
        String zoneId = zone.getZone().getIdentityZone().getId();
        // create mfa provider
        MfaProvider<GoogleMfaProviderConfig> mfaProvider = new MfaProvider();
        mfaProvider.setName(new RandomValueStringGenerator(5).generate());
        mfaProvider.setType(MfaProvider.MfaProviderType.GOOGLE_AUTHENTICATOR);
        mfaProvider.setIdentityZoneId(zone.getZone().getIdentityZone().getId());
        mfaProvider.setConfig((GoogleMfaProviderConfig) new GoogleMfaProviderConfig().setIssuer("issuer"));
        mfaProvider = getWebApplicationContext().getBean(JdbcMfaProviderProvisioning.class).create(mfaProvider, zoneId);
        zone.getZone().getIdentityZone().getConfig().setMfaConfig(new MfaConfig().setEnabled(true).setProviderName(mfaProvider.getName()));
        IdentityZone newZone = getWebApplicationContext().getBean(JdbcIdentityZoneProvisioning.class).update(zone.getZone().getIdentityZone());
        assertEquals(mfaProvider.getName(), newZone.getConfig().getMfaConfig().getProviderName());
        ResultActions actions = performMfaRegistrationInZone(
            "marissa7",
            "ldap7",
            getMockMvc(),
            host,
            new String[]{"ext", "pwd"},
            new String[]{"ext", "pwd", "mfa", "otp"}
        );
        actions
            .andExpect(status().isOk())
            .andExpect(view().name("home"));
    }



    protected void testSuccessfulLogin() throws Exception {
        getMockMvc().perform(post("/login.do").accept(TEXT_HTML_VALUE)
                                 .header(HOST, host)
                                 .with(cookieCsrf())

                                 .param("username", "marissa2")
                                 .param("password", LDAP))
            .andExpect(status().isFound())
            .andExpect(authenticated())
            .andExpect(redirectedUrl("/"));
    }

    @Test
    public void testAuthenticateWithUTF8Characters() throws Exception {
        String username = "\u7433\u8D3A";

        HttpSession session =
            getMockMvc().perform(
                post("/login.do").accept(TEXT_HTML_VALUE)
                    .header(HOST, host)
                    .with(cookieCsrf())
                    .param("username", username)
                    .param("password", "koala"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated())
                .andReturn().getRequest().getSession(false);
        assertNotNull(session);
        assertNotNull(session.getAttribute(SPRING_SECURITY_CONTEXT_KEY));
        Authentication authentication = ((SecurityContext)session.getAttribute(SPRING_SECURITY_CONTEXT_KEY)).getAuthentication();
        assertNotNull(authentication);
        assertTrue(authentication.isAuthenticated());
    }

    @Test
    public void testAuthenticate() throws Exception {
        String username = "marissa3";
        String password = "ldap3";
        MvcResult result = performAuthentication(username, password);
        assertThat(result.getResponse().getContentAsString(), containsString("\"username\":\"" + username + "\""));
        assertThat(result.getResponse().getContentAsString(), containsString("\"email\":\"marissa3@test.com\""));
    }

    @Test
    public void testExtendedAttributes() throws Exception {
        String username = "marissa3";
        String password = "ldap3";
        MvcResult result = performAuthentication(username, password);
        assertThat(result.getResponse().getContentAsString(), containsString("\"username\":\"" + username + "\""));
        assertThat(result.getResponse().getContentAsString(), containsString("\"email\":\"marissa3@test.com\""));
        assertEquals("Marissa", getGivenName(username));
        assertEquals("Lastnamerton", getFamilyName(username));
        assertEquals("8885550986", getPhoneNumber(username));
        assertFalse(getVerified(username));
    }

    @Test
    public void testAuthenticateInactiveIdp() throws Exception {
            provider.setActive(false);
            updateLdapProvider();
            String username = "marissa3";
            String password = "ldap3";
            performAuthentication(username, password, HttpStatus.UNAUTHORIZED);
    }

    @Test
    public void testAuthenticateFailure() throws Exception {
        String username = "marissa3";
        String password = "ldapsadadasas";
        MockHttpServletRequestBuilder post =
            post("/authenticate")
                .header(HOST, host)
                .accept(MediaType.APPLICATION_JSON)
                .param("username", username)
                .param("password", password);
        getMockMvc().perform(post)
            .andExpect(status().isUnauthorized());

        ArgumentCaptor<AbstractUaaEvent> captor = ArgumentCaptor.forClass(AbstractUaaEvent.class);
        verify(listener, atLeast(5)).onApplicationEvent(captor.capture());
        List<AbstractUaaEvent> allValues = captor.getAllValues();
        assertThat(allValues.get(3), instanceOf(IdentityProviderAuthenticationFailureEvent.class));
        IdentityProviderAuthenticationFailureEvent event = (IdentityProviderAuthenticationFailureEvent)allValues.get(3);
        assertEquals("marissa3", event.getUsername());
        assertEquals(OriginKeys.LDAP, event.getAuthenticationType());
    }

    @Test
    public void validateOriginForNonLdapUser() throws Exception {
        String username = "marissa";
        String password = "koala";
        ScimUser user = new ScimUser(null, username, "Marissa","Koala");
        user.setPrimaryEmail("marissa@test.org");
        user.setPassword(password);
        MockMvcUtils.createUserInZone(getMockMvc(), zone.getAdminToken(), user, zone.getZone().getIdentityZone().getSubdomain());

        MvcResult result = performAuthentication(username, password);
        assertThat(result.getResponse().getContentAsString(), containsString("\"username\":\"" + username + "\""));
        assertThat(result.getResponse().getContentAsString(), containsString("\"email\":\"marissa@test.org\""));
        assertEquals(OriginKeys.UAA, getOrigin(username));
    }

    @Test
    public void validateOriginAndEmailForLdapUser() throws Exception {
        String username = "marissa3";
        String password = "ldap3";
        MvcResult result = performAuthentication(username, password);
        assertThat(result.getResponse().getContentAsString(), containsString("\"username\":\"" + username + "\""));
        assertThat(result.getResponse().getContentAsString(), containsString("\"email\":\"marissa3@test.com\""));
        assertEquals(LDAP, getOrigin(username));
        assertEquals("marissa3@test.com",getEmail(username));
    }

    @Test
    public void validateEmailMissingForLdapUser() throws Exception {
        String username = "marissa7";
        String password = "ldap7";
        MvcResult result = performAuthentication(username, password);
        assertThat(result.getResponse().getContentAsString(), containsString("\"username\":\"" + username + "\""));
        assertThat(result.getResponse().getContentAsString(), containsString("\"email\":\"marissa7@user.from.ldap.cf\""));
        assertEquals(LDAP, getOrigin(username));
        assertEquals("marissa7@user.from.ldap.cf", getEmail(username));
    }

    public UaaUser retrieveUserInZone(UaaUserDatabase userDatabase, String email, String origin) {
        try {
            IdentityZoneHolder.set(zone.getZone().getIdentityZone());
            return userDatabase.retrieveUserByEmail(email, origin);
        } finally {
            IdentityZoneHolder.clear();
        }
    }


    @Test
    public void validateCustomEmailForLdapUser() throws Exception {
        provider.getConfig().setMailSubstitute("{0}@ldaptest.org");
        updateLdapProvider();
        String username = "marissa7";
        String password = "ldap7";
        MvcResult result = performAuthentication(username, password);

        assertThat(result.getResponse().getContentAsString(), containsString("\"username\":\"" + username + "\""));
        assertThat(result.getResponse().getContentAsString(), containsString("\"email\":\"marissa7@ldaptest.org\""));
        assertEquals(LDAP, getOrigin(username));
        assertEquals("marissa7@ldaptest.org",getEmail(username));
        provider.getConfig().setMailSubstitute(null);
        updateLdapProvider();

        //null value should go back to default email
        username = "marissa3";
        password = "ldap3";
        result = performAuthentication(username, password);
        assertThat(result.getResponse().getContentAsString(), containsString("\"username\":\"" + username + "\""));
        assertThat(result.getResponse().getContentAsString(), containsString("\"email\":\"marissa3@test.com\""));
        assertEquals(LDAP, getOrigin(username));
        assertEquals("marissa3@test.com",getEmail(username));

        username = "marissa7";
        password = "ldap7";
        result = performAuthentication(username, password);
        assertThat(result.getResponse().getContentAsString(), containsString("\"username\":\"" + username + "\""));
        assertThat(result.getResponse().getContentAsString(), containsString("\"email\":\"marissa7@user.from.ldap.cf\""));
        assertEquals(LDAP, getOrigin(username));
        assertEquals("marissa7@user.from.ldap.cf",getEmail(username));

        //non null value
        provider.getConfig().setMailSubstitute("user-{0}@testldap.org");
        updateLdapProvider();
        result = performAuthentication(username, password);
        assertThat(result.getResponse().getContentAsString(), containsString("\"username\":\"" + username + "\""));
        assertThat(result.getResponse().getContentAsString(), containsString("\"email\":\"user-marissa7@testldap.org\""));
        assertEquals(LDAP, getOrigin(username));
        assertEquals("user-marissa7@testldap.org",getEmail(username));

        //value not overridden
        username = "marissa3";
        password = "ldap3";
        result = performAuthentication(username, password);
        assertThat(result.getResponse().getContentAsString(), containsString("\"username\":\"" + username + "\""));
        assertThat(result.getResponse().getContentAsString(), containsString("\"email\":\"marissa3@test.com\""));
        assertEquals(LDAP, getOrigin(username));
        assertEquals("marissa3@test.com",getEmail(username));

        provider.getConfig().setMailSubstituteOverridesLdap(true);
        updateLdapProvider();
        username = "marissa3";
        password = "ldap3";
        result = performAuthentication(username, password);
        assertThat(result.getResponse().getContentAsString(), containsString("\"username\":\"" + username + "\""));
        assertThat(result.getResponse().getContentAsString(), containsString("\"email\":\"user-marissa3@testldap.org\""));
        assertEquals(LDAP, getOrigin(username));
        assertEquals("user-marissa3@testldap.org",getEmail(username));
    }

    private String getOrigin(String username) throws Exception {
        return getWebApplicationContext().getBean(JdbcTemplate.class).queryForObject("select origin from users where username=? and identity_zone_id=?", String.class, username, zone.getZone().getIdentityZone().getId());
    }

    private String getEmail(String username) throws Exception {
        return getWebApplicationContext().getBean(JdbcTemplate.class).queryForObject("select email from users where username=? and origin=? and identity_zone_id=?", String.class, username, LDAP, zone.getZone().getIdentityZone().getId());
    }

    private String getGivenName(String username) throws Exception {
        return getWebApplicationContext().getBean(JdbcTemplate.class).queryForObject("select givenname from users where username=? and origin=? and identity_zone_id=?", String.class, username, LDAP, zone.getZone().getIdentityZone().getId());
    }

    private String getFamilyName(String username) throws Exception {
        return getWebApplicationContext().getBean(JdbcTemplate.class).queryForObject("select familyname from users where username=? and origin=? and identity_zone_id=?", String.class, username, LDAP, zone.getZone().getIdentityZone().getId());
    }

    private String getPhoneNumber(String username) throws Exception {
        return getWebApplicationContext().getBean(JdbcTemplate.class).queryForObject("select phonenumber from users where username=? and origin=? and identity_zone_id=?", String.class, username, LDAP, zone.getZone().getIdentityZone().getId());
    }

    private boolean getVerified(String username) throws Exception {
        return getWebApplicationContext().getBean(JdbcTemplate.class).queryForObject("select verified from users where username=? and origin=? and identity_zone_id=?", Boolean.class, username, LDAP, zone.getZone().getIdentityZone().getId());
    }

    private MvcResult performAuthentication(String username, String password) throws Exception {
        return performAuthentication(username, password, HttpStatus.OK);
    }

    private MvcResult performAuthentication(String username, String password, HttpStatus status) throws Exception {
        MockHttpServletRequestBuilder post =
            post("/authenticate")
                .header(HOST, host)
                .accept(MediaType.APPLICATION_JSON)
                .param("username", username)
                .param("password", password);

        return getMockMvc().perform(post)
            .andExpect(status().is(status.value()))
            .andReturn();
    }

    private MvcResult performUiAuthentication(String username, String password, HttpStatus status, boolean authenticated) throws Exception {
        MockHttpServletRequestBuilder post =
            post("/login.do")
                .with(cookieCsrf())
                .header(HOST, host)
                .accept(MediaType.TEXT_HTML)
                .param("username", username)
                .param("password", password);

        return getMockMvc().perform(post)
            .andExpect(status().is(status.value()))
            .andExpect(authenticated ? authenticated() : unauthenticated())
            .andReturn();
    }

    @Test
    public void testLdapScopes() throws Exception {
        assumeTrue(ldapGroup.equals("ldap-groups-as-scopes.xml"));
        AuthenticationManager manager = (AuthenticationManager)getBean("ldapAuthenticationManager");
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken("marissa3","ldap3");
        Authentication auth = manager.authenticate(token);
        assertNotNull(auth);
        String[] list = new String[]{
            "uaa.admin",
            "cloud_controller.read",
            "thirdmarissa"
        };
        assertThat(list, arrayContainingInAnyOrder(getAuthorities(auth.getAuthorities())));
    }

    @Test
    public void testLdapScopesFromChainedAuth() throws Exception {
        assumeTrue(ldapGroup.equals("ldap-groups-as-scopes.xml"));
        AuthenticationManager manager = (AuthenticationManager)getWebApplicationContext().getBean("zoneAwareAuthzAuthenticationManager");
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken("marissa3","ldap3");
        IdentityZoneHolder.set(zone.getZone().getIdentityZone());
        Authentication auth = manager.authenticate(token);
        assertNotNull(auth);
        List<String> list = new LinkedList<>(UserConfig.DEFAULT_ZONE_GROUPS);
        list.add("uaa.admin");
        list.add("thirdmarissa");
        list.add("cloud_controller.read");
        assertThat(list, containsInAnyOrder(getAuthorities(auth.getAuthorities())));
        IdentityZoneHolder.clear();
    }

    @Test
    public void testNestedLdapScopes() throws Exception {
        if (!ldapGroup.equals("ldap-groups-as-scopes.xml")) {
            return;
        }
        Set<String> defaultAuthorities = new HashSet(zone.getZone().getIdentityZone().getConfig().getUserConfig().getDefaultGroups());
        AuthenticationManager manager = getWebApplicationContext().getBean(DynamicZoneAwareAuthenticationManager.class);
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken("marissa4","ldap4");
        IdentityZoneHolder.set(zone.getZone().getIdentityZone());
        Authentication auth = manager.authenticate(token);
        assertNotNull(auth);
        defaultAuthorities.addAll(Arrays.asList("test.read", "test.write", "test.everything"));
        assertThat(UaaStringUtils.getStringsFromAuthorities(auth.getAuthorities()), containsInAnyOrder(defaultAuthorities.toArray()));
        IdentityZoneHolder.clear();
    }

    public void transferDefaultMappingsToZone(IdentityZone zone) {
        JdbcScimGroupExternalMembershipManager exm = getWebApplicationContext().getBean(JdbcScimGroupExternalMembershipManager.class);
        ScimGroupProvisioning gp = getWebApplicationContext().getBean(ScimGroupProvisioning.class);
        List<String> defaultMappings = (List<String>) getWebApplicationContext().getBean("defaultExternalMembers");
        IdentityZoneHolder.set(zone);
        for (String s : defaultMappings) {
            String[] groupData = StringUtils.split(s, "|");
            String internalName = groupData[0];
            String externalName = groupData[1];
            ScimGroup group = new ScimGroup(internalName);
            group.setZoneId(zone.getId());
            try {
                group = gp.create(group, IdentityZoneHolder.get().getId());
            } catch (ScimResourceAlreadyExistsException e) {
                String filter = "displayName eq \""+internalName+"\"";
                group = gp.query(filter, IdentityZoneHolder.get().getId()).get(0);
            }
            exm.mapExternalGroup(group.getId(), externalName, OriginKeys.LDAP, zone.getId());
        }
    }

    public void doTestNestedLdapGroupsMappedToScopes(String username, String password, String[] expected) throws Exception {
        assumeTrue(ldapGroup.equals("ldap-groups-map-to-scopes.xml"));
        transferDefaultMappingsToZone(zone.getZone().getIdentityZone());
        IdentityZoneHolder.set(zone.getZone().getIdentityZone());
        AuthenticationManager manager = getWebApplicationContext().getBean(DynamicZoneAwareAuthenticationManager.class);
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(username,password);

        Authentication auth = manager.authenticate(token);
        assertNotNull(auth);
        validateUserAuthorities(expected, auth);
        IdentityZoneHolder.clear();
    }

    protected void validateUserAuthorities(String[] expected, Authentication auth) throws Exception {
        Set<String> defaultAuthorities = new HashSet<>(zone.getZone().getIdentityZone().getConfig().getUserConfig().getDefaultGroups());
        for (String s : expected) {
            defaultAuthorities.add(s);
        }
        assertThat(UaaStringUtils.getStringsFromAuthorities(auth.getAuthorities()), containsInAnyOrder(defaultAuthorities.toArray()));
    }

    @Test
    public void testNestedLdapGroupsMappedToScopes() throws Exception {
        String[] list = new String[] {
            "internal.read",
            "internal.write",
            "internal.everything",
            "internal.superuser"
        };
        doTestNestedLdapGroupsMappedToScopes("marissa4","ldap4",list);
    }

    @Test
    public void testNestedLdapGroupsMappedToScopes2() throws Exception {
        String[] list = new String[] {
            "internal.read",
            "internal.write",
        };
        doTestNestedLdapGroupsMappedToScopes("marissa5","ldap5",list);
    }

    @Test
    public void testNestedLdapGroupsMappedToScopes3() throws Exception {
        String[] list = new String[] {
            "internal.read",
        };
        doTestNestedLdapGroupsMappedToScopes("marissa6","ldap6",list);
    }

    @Test
    public void testNestedLdapGroupsMappedToScopesWithDefaultScopes() throws Exception {
        String username = "marissa4";
        String password = "ldap4";
        String[] list = new String[] {
            "internal.read",
            "internal.write",
            "internal.everything",
            "internal.superuser"
        };
        doTestNestedLdapGroupsMappedToScopesWithDefaultScopes(username, password, list);
    }

    @Test
    public void testNestedLdapGroupsMappedToScopesWithDefaultScopes2() throws Exception {

        String username = "marissa5";
        String password = "ldap5";
        String[] list = new String[] {
            "internal.read",
            "internal.write",
        };
        doTestNestedLdapGroupsMappedToScopesWithDefaultScopes(username,password,list);
    }

    @Test
    public void testNestedLdapGroupsMappedToScopesWithDefaultScopes3() throws Exception {
        String username = "marissa6";
        String password = "ldap6";
        String[] list = new String[] {
            "internal.read",
        };
        doTestNestedLdapGroupsMappedToScopesWithDefaultScopes(username,password,list);
    }

    @Test
    public void testStopIfException() throws Exception {
        if (ldapProfile.equals("ldap-simple-bind.xml") && ldapGroup.equals("ldap-groups-null.xml")) {
            ScimUser user = new ScimUser();
            String userName = "user"+new RandomValueStringGenerator().generate()+"@example.com";
            user.setUserName(userName);
            user.addEmail(userName);
            user.setVerified(true);
            user.setPassword("n1cel0ngp455w0rd");
            user = MockMvcUtils.createUserInZone(getMockMvc(), zone.getAdminToken(), user, zone.getZone().getIdentityZone().getSubdomain());
            assertNotNull(user.getId());
            performAuthentication(userName, "n1cel0ngp455w0rd", HttpStatus.OK);
        }
    }

    public void doTestNestedLdapGroupsMappedToScopesWithDefaultScopes(String username, String password, String[] expected) throws Exception {
        assumeTrue(ldapGroup.equals("ldap-groups-map-to-scopes.xml"));
        AuthenticationManager manager = getWebApplicationContext().getBean(DynamicZoneAwareAuthenticationManager.class);
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(username,password);
        transferDefaultMappingsToZone(zone.getZone().getIdentityZone());
        IdentityZoneHolder.set(zone.getZone().getIdentityZone());
        Authentication auth = manager.authenticate(token);
        assertNotNull(auth);
        Set<String> defaultAuthorities = new HashSet(zone.getZone().getIdentityZone().getConfig().getUserConfig().getDefaultGroups());
        defaultAuthorities.addAll(Arrays.asList(expected));
        assertThat(UaaStringUtils.getStringsFromAuthorities(auth.getAuthorities()), containsInAnyOrder(defaultAuthorities.toArray()));
    }

    public String[] getAuthorities(Collection<? extends GrantedAuthority> authorities) {
        String[] result = new String[authorities!=null?authorities.size():0];
        if (result.length>0) {
            int index=0;
            for (GrantedAuthority a : authorities) {
                result[index++] = a.getAuthority();
            }
        }
        return result;
    }

    public void updateLdapProvider() throws Exception {
        provider = MockMvcUtils.createIdpUsingWebRequest(
            getMockMvc(),
            zone.getZone().getIdentityZone().getId(),
            zone.getZone().getZoneAdminToken(),
            provider,
            status().isOk(),
            true
        );
    }
}
