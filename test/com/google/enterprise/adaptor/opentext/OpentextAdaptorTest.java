// Copyright 2015 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.adaptor.opentext;

import static com.google.enterprise.adaptor.opentext.OpentextAdaptor.SoapFactory;
import static com.google.enterprise.adaptor.opentext.OpentextAdaptor.SoapFactoryImpl;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.enterprise.adaptor.Acl;
import com.google.enterprise.adaptor.AdaptorContext;
import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.DocId;
import com.google.enterprise.adaptor.DocIdPusher;
import com.google.enterprise.adaptor.GroupPrincipal;
import com.google.enterprise.adaptor.IOHelper;
import com.google.enterprise.adaptor.InvalidConfigurationException;
import com.google.enterprise.adaptor.Principal;
import com.google.enterprise.adaptor.Request;
import com.google.enterprise.adaptor.Response;
import com.google.enterprise.adaptor.UserPrincipal;

import com.opentext.ecm.services.authws.AuthenticationException;
import com.opentext.ecm.services.authws.AuthenticationException_Exception;
import com.opentext.livelink.service.collaboration.Collaboration;
import com.opentext.livelink.service.collaboration.DiscussionItem;
import com.opentext.livelink.service.collaboration.MilestoneInfo;
import com.opentext.livelink.service.collaboration.NewsInfo;
import com.opentext.livelink.service.collaboration.ProjectInfo;
import com.opentext.livelink.service.collaboration.ProjectStatus;
import com.opentext.livelink.service.collaboration.TaskInfo;
import com.opentext.livelink.service.collaboration.TaskPriority;
import com.opentext.livelink.service.collaboration.TaskStatus;
import com.opentext.livelink.service.core.Authentication;
import com.opentext.livelink.service.core.BooleanValue;
import com.opentext.livelink.service.core.ContentService;
import com.opentext.livelink.service.core.DateValue;
import com.opentext.livelink.service.core.IntegerValue;
import com.opentext.livelink.service.core.PageHandle;
import com.opentext.livelink.service.core.RowValue;
import com.opentext.livelink.service.core.StringValue;
import com.opentext.livelink.service.core.TableValue;
import com.opentext.livelink.service.docman.Attribute;
import com.opentext.livelink.service.docman.AttributeGroup;
import com.opentext.livelink.service.docman.AttributeGroupDefinition;
import com.opentext.livelink.service.docman.DocumentManagement;
import com.opentext.livelink.service.docman.Metadata;
import com.opentext.livelink.service.docman.Node;
import com.opentext.livelink.service.docman.NodeFeature;
import com.opentext.livelink.service.docman.NodePermissions;
import com.opentext.livelink.service.docman.NodeRight;
import com.opentext.livelink.service.docman.NodeRights;
import com.opentext.livelink.service.docman.NodeVersionInfo;
import com.opentext.livelink.service.docman.PrimitiveAttribute;
import com.opentext.livelink.service.docman.SetAttribute;
import com.opentext.livelink.service.docman.UserAttribute;
import com.opentext.livelink.service.docman.Version;
import com.opentext.livelink.service.memberservice.Group;
import com.opentext.livelink.service.memberservice.Member;
import com.opentext.livelink.service.memberservice.MemberPrivileges;
import com.opentext.livelink.service.memberservice.MemberSearchOptions;
import com.opentext.livelink.service.memberservice.MemberSearchResults;
import com.opentext.livelink.service.memberservice.MemberService;
import com.opentext.livelink.service.memberservice.SearchFilter;
import com.opentext.livelink.service.memberservice.User;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPFactory;
import javax.xml.soap.SOAPFault;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.soap.SOAPFaultException;

/**
 * Tests the OpentextAdaptor class.
 */
public class OpentextAdaptorTest {
  /** The namespaces when this test's initConfig helper is used. */
  private static final String GLOBAL_NAMESPACE = "globalnamespace";
  private static final String LOCAL_NAMESPACE =
      GLOBAL_NAMESPACE + "_example-com";

  private static final String RESPONSE_NO_RESULTS =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
      + "<Output>"
      + "  <SearchResultsInformation>"
      + "    <CurrentStartAt>1</CurrentStartAt>"
      + "    <NumberResultsThisPage>0</NumberResultsThisPage>"
      + "  </SearchResultsInformation>"
      + "</Output>";

  @BeforeClass
  public static void setUpClass() {
    // Tests trigger warning logs in the adaptor; remove those
    // stack traces from test output.
    Logger.getLogger(OpentextAdaptor.class.getName()).setLevel(Level.SEVERE);
  }

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  /**
   * Verify that the ENDPOINT_ADDRESS_PROPERTY is set.
   */
  @Test
  public void testSoapFactoryImplServerTomcat() {
    Config config = new Config();
    config.addKey("opentext.directoryServicesUrl", "");
    config.addKey("opentext.webServicesUrl", "webServicesUrl/");
    config.addKey("opentext.webServicesServer", "Tomcat");
    SoapFactoryImpl factory = new SoapFactoryImpl();
    factory.configure(config);
    Authentication authentication = factory.newAuthentication();
    assertEquals("webServicesUrl/Authentication",
        ((BindingProvider) authentication).getRequestContext().get(
            BindingProvider.ENDPOINT_ADDRESS_PROPERTY));
  }

  @Test
  public void testSoapFactoryImplServerUnset() {
    SoapFactoryImpl soapFactory = new SoapFactoryImpl();
    Config config = new Config();
    config.addKey("opentext.directoryServicesUrl", "");
    config.addKey("opentext.webServicesUrl", "webServicesUrl");
    config.addKey("opentext.webServicesServer", "");

    soapFactory.configure(config);
    Authentication authentication = soapFactory.newAuthentication();
    assertEquals("webServicesUrl/Authentication.svc",
        ((BindingProvider) authentication).getRequestContext().get(
            BindingProvider.ENDPOINT_ADDRESS_PROPERTY));
  }

  @Test
  public void testSoapFactoryImplServerIis() {
    SoapFactoryImpl soapFactory = new SoapFactoryImpl();
    Config config = new Config();
    config.addKey("opentext.directoryServicesUrl", "");
    config.addKey("opentext.webServicesUrl", "webServicesUrl");
    config.addKey("opentext.webServicesServer", "IIS");

    soapFactory.configure(config);
    Authentication authentication = soapFactory.newAuthentication();
    assertEquals("webServicesUrl/Authentication.svc",
        ((BindingProvider) authentication).getRequestContext().get(
            BindingProvider.ENDPOINT_ADDRESS_PROPERTY));
  }

  /**
   * Check that trailing slashes or the lack thereof are handled
   * on the webServicesUrl property.
   */
  @Test
  public void testSoapFactoryImplGetWebServiceAddress() {
    Config config = new Config();
    config.addKey("opentext.directoryServicesUrl", "");
    config.addKey("opentext.webServicesUrl", "webServicesUrl");
    config.addKey("opentext.webServicesServer", "Tomcat");
    SoapFactoryImpl factory = new SoapFactoryImpl();
    factory.configure(config);
    assertEquals("webServicesUrl/Authentication",
        factory.getWebServiceAddress("Authentication"));

    config.overrideKey("opentext.webServicesUrl", "webServicesUrl/");
    factory.configure(config);
    assertEquals("webServicesUrl/Authentication",
        factory.getWebServiceAddress("Authentication"));
  }

  @Test
  public void testAuthenticateUser() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    assertFalse("authUser called before init",
        soapFactory.authenticationMock.authenticateUserCalled);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    assertTrue("authUser not called after init",
        soapFactory.authenticationMock.authenticateUserCalled);
    assertEquals("unexpected authentication token", "authentication_token",
        soapFactory.authenticationMock.authenticationToken);
  }

  @Test
  public void testAuthenticateUserInvalidUser() {
    thrown.expect(InvalidConfigurationException.class);
    thrown.expectMessage("javax.xml.ws.soap.SOAPFaultException");

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    assertFalse("authUser called before init",
        soapFactory.authenticationMock.authenticateUserCalled);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.username", "invaliduser");
    config.overrideKey("opentext.password", "validpassword");
    adaptor.init(context);
  }

  @Test
  public void testAuthenticateUserInvalidPassword() {
    thrown.expect(InvalidConfigurationException.class);
    thrown.expectMessage("Invalid username/password specified.");

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    assertFalse("authUser called before init",
        soapFactory.authenticationMock.authenticateUserCalled);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.username", "validuser");
    config.overrideKey("opentext.password", "invalidpassword");
    adaptor.init(context);
  }

  @Test
  public void testAuthenticateUserOtherSoapException() {
    thrown.expect(SOAPFaultException.class);
    thrown.expectMessage("Other SOAPFaultException");

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    assertFalse("authUser called before init",
        soapFactory.authenticationMock.authenticateUserCalled);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.username", "validuser");
    config.overrideKey("opentext.password", "other");
    adaptor.init(context);
  }

  @Test
  public void testAuthenticateAdminUserInvalidUser() {
    thrown.expect(InvalidConfigurationException.class);
    thrown.expectMessage("javax.xml.ws.soap.SOAPFaultException");

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    assertFalse("authUser called before init",
        soapFactory.authenticationMock.authenticateUserCalled);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.adminUsername", "invaliduser");
    config.overrideKey("opentext.adminPassword", "validpassword");
    adaptor.init(context);
  }

  @Test
  public void testAuthenticateUserDirectoryServices() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    assertFalse("authenticate called before init",
        soapFactory.dsAuthenticationMock.authenticateCalled);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.directoryServicesUrl", "/otdsws/");
    adaptor.init(context);
    assertFalse("authUser called after init",
        soapFactory.authenticationMock.authenticateUserCalled);
    assertTrue("authenticate not called after init",
        soapFactory.dsAuthenticationMock.authenticateCalled);
    assertTrue("validateUser not called after init",
        soapFactory.authenticationMock.validateUserCalled);
    assertEquals("unexpected authentication token", "validation_token",
        soapFactory.authenticationMock.authenticationToken);
  }

  @Test
  public void testAuthenticateUserDirectoryServicesInvalidUser() {
    thrown.expect(InvalidConfigurationException.class);
    thrown.expectMessage("Authentication failed");

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    assertFalse("authenticate called before init",
        soapFactory.dsAuthenticationMock.authenticateCalled);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.directoryServicesUrl", "/otdsws/");

    soapFactory.dsAuthenticationMock.faultCode =
        "AuthenticationService.Application.AuthenticationFailed";
    soapFactory.dsAuthenticationMock.message = "Authentication failed";
    adaptor.init(context);
  }

  @Test
  public void testAuthenticateUserDirectoryServicesOtherError() {
    thrown.expect(SOAPFaultException.class);
    thrown.expectMessage("Other failure");

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    assertFalse("authenticate called before init",
        soapFactory.dsAuthenticationMock.authenticateCalled);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.directoryServicesUrl", "/otdsws/");

    soapFactory.dsAuthenticationMock.faultCode = "AuthenticationService.Other";
    soapFactory.dsAuthenticationMock.message = "Other failure";
    adaptor.init(context);
  }

  @Test
  public void testDefaultStartPoints() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    List<StartPoint> startPoints = adaptor.getStartPoints();
    assertEquals(1, startPoints.size());
    assertStartPointEquals(startPoints.get(0),
        StartPoint.Type.VOLUME, "EnterpriseWS", 2000);
  }

  @Test
  public void testInitNoStartPoints() {
    thrown.expect(InvalidConfigurationException.class);
    thrown.expectMessage("No valid opentext.src values");

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.src", "");
    adaptor.init(context);
  }

  @Test
  public void testNodeStartPoints() {
    List<StartPoint> startPoints
        = OpentextAdaptor.getStartPoints("11, 12, 13", ",");
    assertEquals(3, startPoints.size());
    assertStartPointEquals(startPoints.get(0),
        StartPoint.Type.NODE, "11", 11);
    assertStartPointEquals(startPoints.get(1),
        StartPoint.Type.NODE, "12", 12);
    assertStartPointEquals(startPoints.get(2),
        StartPoint.Type.NODE, "13", 13);
  }

  @Test
  public void testMixedStartPoints() {
    List<StartPoint> startPoints =
        OpentextAdaptor.getStartPoints("11, 12, EnterpriseWS", ",");
    assertEquals(3, startPoints.size());
    assertStartPointEquals(startPoints.get(0),
        StartPoint.Type.NODE, "11", 11);
    assertStartPointEquals(startPoints.get(1),
        StartPoint.Type.NODE, "12", 12);
    assertStartPointEquals(startPoints.get(2),
        StartPoint.Type.VOLUME, "EnterpriseWS", -1);
  }

  @Test
  public void testInvalidStartPoints() {
    List<StartPoint> startPoints =
        OpentextAdaptor.getStartPoints(
            "11x, 12, EnterpriseWS, My Favorite Folder", ",");
    assertEquals(2, startPoints.size());
    assertStartPointEquals(startPoints.get(0),
        StartPoint.Type.NODE, "12", 12);
    assertStartPointEquals(startPoints.get(1),
        StartPoint.Type.VOLUME, "EnterpriseWS", -1);
  }

  @Test
  public void testNoValidStartPoints() {
    List<StartPoint> startPoints = OpentextAdaptor.getStartPoints(
        "11x, , My Favorite Folder", ",");
    assertEquals(0, startPoints.size());
  }

  @Test
  public void testStartPointSeparator() {
    List<StartPoint> startPoints = OpentextAdaptor.getStartPoints(
        "11x : 12 : EnterpriseWS : My Favorite Folder", ":");
    assertEquals(2, startPoints.size());
    assertStartPointEquals(startPoints.get(0),
        StartPoint.Type.NODE, "12", 12);
    assertStartPointEquals(startPoints.get(1),
        StartPoint.Type.VOLUME, "EnterpriseWS", -1);
  }

  @Test
  public void testDefaultExcludedNodeTypes() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    List<String> excludedNodeTypes = adaptor.getExcludedNodeTypes();
    assertEquals(0, excludedNodeTypes.size());
  }

  @Test
  public void testGetExcludedNodeTypes() {
    // init will remove "Folder" from this property, but
    // getExcludedNodeTypes simply reads and processes the config
    // value as entered.
    List<String> excludedNodeTypes =
        OpentextAdaptor.getExcludedNodeTypes("Folder, 432", ",");
    assertEquals(
        Lists.newArrayList("Folder", "GenericNode:432"), excludedNodeTypes);
  }

  @Test
  public void testEmptyExcludedNodeTypes() {
    List<String> excludedNodeTypes =
        OpentextAdaptor.getExcludedNodeTypes("", ",");
    assertEquals(0, excludedNodeTypes.size());
  }

  @Test
  public void testExcludedNodeTypesConfiguredWithFolder() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.excludedNodeTypes", "Folder, Document");
    adaptor.init(context);
    List<String> excludedNodeTypes = adaptor.getExcludedNodeTypes();
    assertEquals(Lists.newArrayList("Document"), excludedNodeTypes);
  }

  @Test
  public void testGetLocalNamespace() {
    assertEquals("Default_www-example-com",
        OpentextAdaptor.getLocalNamespace(
            "Default", "https://www.example.com/CS/cs.exe"));
    assertEquals("Default_www-example-com_42",
        OpentextAdaptor.getLocalNamespace(
            "Default", "https://www.example.com:42/CS/cs.exe"));
    assertEquals("Default_www-2-example-com_42",
        OpentextAdaptor.getLocalNamespace(
            "Default", "https://www-2.example.com:42/CS/cs.exe"));
    assertEquals("Default_1-1-1-1_42",
        OpentextAdaptor.getLocalNamespace(
            "Default", "https://1.1.1.1:42/CS/cs.exe"));
  }

  @Test
  public void testDefaultGetDocIds() throws InterruptedException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    DocIdPusherMock docIdPusherMock = new DocIdPusherMock();
    adaptor.getDocIds(
        Proxies.newProxyInstance(DocIdPusher.class, docIdPusherMock));
    assertEquals(1, docIdPusherMock.docIds.size());
    assertEquals(
        "EnterpriseWS:2000", docIdPusherMock.docIds.get(0).getUniqueId());
  }

  @Test
  public void testValidateDocIds() throws InterruptedException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.src", "1001, 1002, 1003");
    adaptor.init(context);

    DocIdPusherMock docIdPusherMock = new DocIdPusherMock();
    adaptor.getDocIds(
        Proxies.newProxyInstance(DocIdPusher.class, docIdPusherMock));
    assertEquals(2, docIdPusherMock.docIds.size());
    assertEquals("1001:1001", docIdPusherMock.docIds.get(0).getUniqueId());
    assertEquals("1003:1003", docIdPusherMock.docIds.get(1).getUniqueId());
  }

  @Test
  public void testGetDocIdsMarkPublicTrue() throws InterruptedException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("adaptor.markAllDocsAsPublic", "true");
    adaptor.init(context);

    soapFactory.memberServiceMock.addMember(
        getMember(1000, "user1", "User"));
    soapFactory.memberServiceMock.addMember(
        getMember(2000, "group1", "Group"));
    soapFactory.memberServiceMock.addMemberToGroup(
        2000, soapFactory.memberServiceMock.getMemberById(1000));

    DocIdPusherMock docIdPusherMock = new DocIdPusherMock();
    adaptor.getDocIds(
        Proxies.newProxyInstance(DocIdPusher.class, docIdPusherMock));
    assertEquals(null, docIdPusherMock.groupDefinitions);
  }

  @Test
  public void testGetDocIdsMarkPublicFalse() throws InterruptedException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("adaptor.markAllDocsAsPublic", "false");
    adaptor.init(context);

    soapFactory.memberServiceMock.addMember(
        getMember(1000, "user1", "User"));
    soapFactory.memberServiceMock.addMember(
        getMember(2000, "group1", "Group"));
    soapFactory.memberServiceMock.addMemberToGroup(
        2000, soapFactory.memberServiceMock.getMemberById(1000));

    DocIdPusherMock docIdPusherMock = new DocIdPusherMock();
    adaptor.getDocIds(
        Proxies.newProxyInstance(DocIdPusher.class, docIdPusherMock));
    Map<GroupPrincipal, List<Principal>> expected =
        new HashMap<GroupPrincipal, List<Principal>>();
    expected.put(newGroupPrincipal("group1"),
        Lists.<Principal>newArrayList(newUserPrincipal("user1")));
    assertEquals(expected, docIdPusherMock.groupDefinitions);
  }

  @Test
  public void testGetNodeStartPoint() throws InterruptedException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.src", "EnterpriseWS");
    adaptor.init(context);

    DocumentManagement documentManagement =
        soapFactory.newDocumentManagement("token");
    Node node = adaptor.getNode(documentManagement,
        new OpentextDocId(new DocId("EnterpriseWS:2000")));
    assertNotNull(node);
    assertEquals(2000, node.getID());
    assertEquals("Enterprise Workspace", node.getName());

    assertNull(adaptor.getNode(documentManagement,
            new OpentextDocId(new DocId("InvalidStartPoint:1111"))));
  }

  @Test
  public void testGetNodePath() throws InterruptedException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    DocumentManagement documentManagement =
        soapFactory.newDocumentManagement("token");

    NodeMock testNode = new NodeMock(3214, "Important Document");
    testNode.setStartPointId(2000);
    testNode.setPath("folder 1", "folder 2", "Important Document");
    soapFactory.documentManagementMock.addNode(testNode);

    DocId docId =
        new DocId("EnterpriseWS/folder+1/folder+2/Important+Document:3214");
    Node node = adaptor.getNode(documentManagement, new OpentextDocId(docId));

    assertNotNull("Couldn't find test node", node);
    assertEquals(3214, node.getID());
    assertEquals("Important Document", node.getName());
  }

  @Test
  public void testGetNodePathWithVolume() throws InterruptedException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();

    NodeMock projectNode = new NodeMock(3214, "Important Project");
    projectNode.setStartPointId(2000);
    projectNode.setParentID(2000);
    projectNode.setPath("Important Project");
    soapFactory.documentManagementMock.addNode(projectNode);

    NodeMock folderInProjectNode = new NodeMock(4100, "Folder in Project");
    folderInProjectNode.setStartPointId(2000);
    folderInProjectNode.setParentID(-1 * projectNode.getID());
    folderInProjectNode.setPath("Important Project", "Folder in Project");
    soapFactory.documentManagementMock.addNode(folderInProjectNode);

    NodeMock documentNode = new NodeMock(5100, "Document under Project");
    documentNode.setStartPointId(2000);
    documentNode.setParentID(folderInProjectNode.getID());
    documentNode.setPath(
        "Important Project", "Folder in Project", "Document under Project");
    soapFactory.documentManagementMock.addNode(documentNode);

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.src", "EnterpriseWS," + projectNode.getID());
    adaptor.init(context);

    DocumentManagement documentManagement =
        soapFactory.newDocumentManagement("token");

    DocId docId = new DocId("EnterpriseWS/Important+Project/Folder+in+Project:"
        + folderInProjectNode.getID());
    Node node = adaptor.getNode(documentManagement, new OpentextDocId(docId));
    assertNotNull("Couldn't find test node", node);
    assertEquals(folderInProjectNode.getID(), node.getID());
    assertEquals(folderInProjectNode.getName(), node.getName());

    docId = new DocId("EnterpriseWS/Important+Project/Folder+in+Project/"
        + "Document+under+Project:" + documentNode.getID());
    node = adaptor.getNode(documentManagement, new OpentextDocId(docId));
    assertNotNull("Couldn't find test node", node);
    assertEquals(documentNode.getID(), node.getID());
    assertEquals(documentNode.getName(), node.getName());

    // Test nodes with the project as the start point.
    docId = new DocId(projectNode.getID() + "/Folder+in+Project/"
        + "Document+under+Project:" + documentNode.getID());
    node = adaptor.getNode(documentManagement, new OpentextDocId(docId));
    assertNotNull("Couldn't find test node", node);
    assertEquals(documentNode.getID(), node.getID());
    assertEquals(documentNode.getName(), node.getName());

    docId = new DocId(projectNode.getID()
        + "/Folder+in+Project:" + folderInProjectNode.getID());
    node = adaptor.getNode(documentManagement, new OpentextDocId(docId));
    assertNotNull("Couldn't find test node", node);
    assertEquals(folderInProjectNode.getID(), node.getID());
    assertEquals(folderInProjectNode.getName(), node.getName());
  }

  @Test
  public void testGetChildDocId() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);

    OpentextDocId parentOpentextDocId =
        new OpentextDocId(new DocId("456:456"));
    DocId childDocId =
        adaptor.getChildDocId(parentOpentextDocId, "Child Name", 123);
    assertEquals("456/Child+Name:123", childDocId.getUniqueId());
    parentOpentextDocId =
        new OpentextDocId(new DocId("EnterpriseWS/Folder/Container/Doc:678"));
    childDocId =
        adaptor.getChildDocId(parentOpentextDocId, "Child Name", 123);
    assertEquals("EnterpriseWS/Folder/Container/Doc/Child+Name:123",
        childDocId.getUniqueId());
  }

  @Test
  public void testGetDocContentIndexFoldersTrue() throws IOException {
    Member owner = getMember(1001, "testuser1", "User");
    NodeRights nodeRights = new NodeRights();
    nodeRights.setOwnerRight(getNodeRight(owner.getID(), "Owner"));
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    NodeMock node = new NodeMock(3143, "Folder Name", "Folder");
    node.setStartPointId(2000);
    node.setPath(node.getName());
    soapFactory.documentManagementMock.addNode(node);
    soapFactory.documentManagementMock
        .setNodeRights(node.getID(), nodeRights);
    soapFactory.memberServiceMock.addMember(owner);

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.indexFolders", "true");
    adaptor.init(context);

    ResponseMock responseMock = new ResponseMock();
    Response response =
        Proxies.newProxyInstance(Response.class, responseMock);
    RequestMock requestMock = new RequestMock(
        new DocId("EnterpriseWS/Folder+Name:3143"));
    Request request = Proxies.newProxyInstance(Request.class,
        requestMock);
    adaptor.getDocContent(request, response);
    assertEquals("http://example.com/otcs/livelink.exe"
        + "?func=ll&objAction=properties&objId=3143",
        responseMock.displayUrl.toString());
    assertFalse(responseMock.noIndex);
  }

  @Test
  public void testGetDocContentIndexFoldersFalse() throws IOException {
    Member owner = getMember(1001, "testuser1", "User");
    NodeRights nodeRights = new NodeRights();
    nodeRights.setOwnerRight(getNodeRight(owner.getID(), "Owner"));
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    NodeMock node = new NodeMock(3143, "Folder Name", "Folder");
    node.setStartPointId(2000);
    node.setPath(node.getName());
    soapFactory.documentManagementMock.addNode(node);
    soapFactory.documentManagementMock
        .setNodeRights(node.getID(), nodeRights);
    soapFactory.memberServiceMock.addMember(owner);

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.indexFolders", "false");
    adaptor.init(context);

    ResponseMock responseMock = new ResponseMock();
    Response response =
        Proxies.newProxyInstance(Response.class, responseMock);
    RequestMock requestMock = new RequestMock(
        new DocId("EnterpriseWS/Folder+Name:3143"));
    Request request = Proxies.newProxyInstance(Request.class,
        requestMock);
    adaptor.getDocContent(request, response);
    assertEquals("http://example.com/otcs/livelink.exe"
        + "?func=ll&objAction=properties&objId=3143",
        responseMock.displayUrl.toString());
    assertTrue(responseMock.noIndex);
    assertEquals(
        Lists.newArrayList("3143"), responseMock.getMetadata().get("ID"));
    assertEquals(Lists.newArrayList("Folder Name"),
        responseMock.getMetadata().get("Name"));
  }

  @Test
  public void testGetDocContentMarkPublicTrue() throws IOException {
    Member owner = getMember(1001, "testuser1", "User");
    NodeRights nodeRights = new NodeRights();
    nodeRights.setOwnerRight(getNodeRight(owner.getID(), "Owner"));
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    NodeMock node = new NodeMock(3143, "Folder Name", "Folder");
    node.setStartPointId(2000);
    node.setPath(node.getName());
    soapFactory.documentManagementMock.addNode(node);
    soapFactory.documentManagementMock
        .setNodeRights(node.getID(), nodeRights);
    soapFactory.memberServiceMock.addMember(owner);

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("adaptor.markAllDocsAsPublic", "true");
    adaptor.init(context);

    ResponseMock responseMock = new ResponseMock();
    Response response =
        Proxies.newProxyInstance(Response.class, responseMock);
    RequestMock requestMock = new RequestMock(
        new DocId("EnterpriseWS/Folder+Name:3143"));
    Request request = Proxies.newProxyInstance(Request.class,
        requestMock);
    adaptor.getDocContent(request, response);
    assertEquals(null, responseMock.getAcl());
  }

  @Test
  public void testGetDocContentMarkPublicFalse() throws IOException {
    Member owner = getMember(1001, "testuser1", "User");
    NodeRights nodeRights = new NodeRights();
    nodeRights.setOwnerRight(getNodeRight(owner.getID(), "Owner"));
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    NodeMock node = new NodeMock(3143, "Folder Name", "Folder");
    node.setStartPointId(2000);
    node.setPath(node.getName());
    soapFactory.documentManagementMock.addNode(node);
    soapFactory.documentManagementMock
        .setNodeRights(node.getID(), nodeRights);
    soapFactory.memberServiceMock.addMember(owner);

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("adaptor.markAllDocsAsPublic", "false");
    adaptor.init(context);

    ResponseMock responseMock = new ResponseMock();
    Response response =
        Proxies.newProxyInstance(Response.class, responseMock);
    RequestMock requestMock = new RequestMock(
        new DocId("EnterpriseWS/Folder+Name:3143"));
    Request request = Proxies.newProxyInstance(Request.class,
        requestMock);
    adaptor.getDocContent(request, response);
    Acl expected = new Acl.Builder()
        .setPermitUsers(Sets.newHashSet(newUserPrincipal("testuser1")))
        .build();
    assertEquals(expected, responseMock.getAcl());
  }

  @Test
  public void testDoContainer() throws IOException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();

    // Set up a test folder with content.
    NodeMock containerNode = new NodeMock(3000, "Folder");
    containerNode.setStartPointId(2000);
    containerNode.setPath("Folder");
    soapFactory.documentManagementMock.addNode(containerNode);
    for (int i = 1; i <= 3; i++) {
      NodeMock testNode = new NodeMock(4000 + i, "Document " + i);
      testNode.setStartPointId(2000);
      testNode.setPath("Folder", "Document " + i);
      soapFactory.documentManagementMock.addNode(testNode);
    }

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    ResponseMock responseMock = new ResponseMock();
    Response response = Proxies.newProxyInstance(Response.class,
        responseMock);

    DocumentManagement documentManagement =
        soapFactory.newDocumentManagement("token");
    adaptor.doContainer(documentManagement,
        new OpentextDocId(new DocId("2000/Folder:3000")),
        containerNode, response);

    // I think the links don't get relativized because I'm not
    // creating anything with a scheme in the tests.
    String expected = "<!DOCTYPE html>\n"
        + "<html><head><title>Folder 2000/Folder:3000</title></head>"
        + "<body><h1>Folder 2000/Folder:3000</h1>"
        + "<li><a href=\"2000/Folder/Document+1:4001\">Document 1</a></li>"
        + "<li><a href=\"2000/Folder/Document+2:4002\">Document 2</a></li>"
        + "<li><a href=\"2000/Folder/Document+3:4003\">Document 3</a></li>"
        + "</body></html>";
    assertEquals(expected,
        responseMock.outputStream.toString("UTF-8"));
  }

  @Test
  public void testGetDisplayUrl() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.addKey("opentext.displayUrl.objAction.111", "actionFor111");
    adaptor.init(context);

    URI displayUrl = adaptor.getDisplayUrl("Document", 12345);
    assertEquals("http://example.com/otcs/livelink.exe"
        + "?func=ll&objAction=overview&objId=12345", displayUrl.toString());

    displayUrl = adaptor.getDisplayUrl("UnknownType", 12345);
    assertEquals("http://example.com/otcs/livelink.exe"
        + "?func=ll&objAction=properties&objId=12345", displayUrl.toString());

    displayUrl = adaptor.getDisplayUrl("GenericNode:111", 12345);
    assertEquals("http://example.com/otcs/livelink.exe"
        + "?func=ll&objAction=actionFor111&objId=12345", displayUrl.toString());
  }

  @Test
  public void testGetDisplayUrlPathInfo() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.addKey(
        "opentext.displayUrl.queryString.Document", "/open/{1}");
    config.addKey(
        "opentext.displayUrl.queryString.111", "/open111/{1}");
    adaptor.init(context);

    URI displayUrl = adaptor.getDisplayUrl("Document", 12345);
    assertEquals("http://example.com/otcs/livelink.exe/open/12345",
        displayUrl.toString());
    displayUrl = adaptor.getDisplayUrl("GenericNode:111", 12345);
    assertEquals("http://example.com/otcs/livelink.exe/open111/12345",
        displayUrl.toString());
  }

  @Test
  public void testDoDocumentNoVersions() throws IOException {
    DocId docId = new DocId("2000/Document Name:3143");
    OpentextDocId testDocId = new OpentextDocId(docId);

    thrown.expect(RuntimeException.class);
    thrown.expectMessage(
        "Document does not support versions: " + testDocId);

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    NodeMock documentNode = new NodeMock(3143, "Document Name");
    documentNode.setIsVersionable(false);
    soapFactory.documentManagementMock.addNode(documentNode);

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    RequestMock requestMock = new RequestMock(docId);
    Request request = Proxies.newProxyInstance(Request.class, requestMock);
    ResponseMock responseMock = new ResponseMock();
    Response response = Proxies.newProxyInstance(Response.class,
        responseMock);

    DocumentManagement documentManagement =
        soapFactory.newDocumentManagement("token");
    adaptor.doDocument(documentManagement, testDocId,
        documentNode, request, response);
  }

  @Test
  public void testDoDocument() throws IOException {
    DocId docId = new DocId("2000/Document:3143");
    OpentextDocId testDocId = new OpentextDocId(docId);
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    NodeMock documentNode =
        new NodeMock(3143, "Title of Document", "Document");
    documentNode.setVersion(1, "text/plain",
        new GregorianCalendar(2015, 1, 3, 9, 42, 42));
    soapFactory.documentManagementMock.addNode(documentNode);

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    RequestMock requestMock = new RequestMock(docId);
    Request request = Proxies.newProxyInstance(Request.class, requestMock);
    ResponseMock responseMock = new ResponseMock();
    Response response = Proxies.newProxyInstance(Response.class,
        responseMock);

    DocumentManagement documentManagement =
        soapFactory.newDocumentManagement("token");
    adaptor.doDocument(documentManagement, testDocId,
        documentNode, request, response);

    assertEquals("text/plain", responseMock.contentType);
    assertEquals("this is the content",
        responseMock.outputStream.toString("UTF-8"));
  }

  @Test
  public void testDoDocumentEmpty() throws IOException {
    DocId docId = new DocId("2000/Document:3143");
    OpentextDocId testDocId = new OpentextDocId(docId);
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    NodeMock documentNode =
        new NodeMock(3143, "Title of Document", "Document");
    documentNode.setVersion(1, "text/plain",
        new GregorianCalendar(2015, 1, 3, 9, 42, 42));
    documentNode.getVersion().setFileDataSize(0L);
    soapFactory.documentManagementMock.addNode(documentNode);

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    RequestMock requestMock = new RequestMock(docId);
    Request request = Proxies.newProxyInstance(Request.class, requestMock);
    ResponseMock responseMock = new ResponseMock();
    Response response =
        Proxies.newProxyInstance(Response.class, responseMock);

    DocumentManagement documentManagement =
        soapFactory.newDocumentManagement("token");
    adaptor.doDocument(documentManagement, testDocId,
        documentNode, request, response);

    assertNull(responseMock.contentType);
    assertEquals("",
        responseMock.outputStream.toString(UTF_8.name()));
  }

  @Test
  public void testDoDocumentLarge() throws IOException {
    DocId docId = new DocId("2000/Document:3143");
    OpentextDocId testDocId = new OpentextDocId(docId);
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    NodeMock documentNode =
        new NodeMock(3143, "Title of Document", "Document");
    documentNode.setVersion(1, "text/plain",
        new GregorianCalendar(2015, 1, 3, 9, 42, 42));
    documentNode.getVersion().setFileDataSize(3L << 30);
    soapFactory.documentManagementMock.addNode(documentNode);

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    RequestMock requestMock = new RequestMock(docId);
    Request request = Proxies.newProxyInstance(Request.class, requestMock);
    ResponseMock responseMock = new ResponseMock();
    Response response =
        Proxies.newProxyInstance(Response.class, responseMock);

    DocumentManagement documentManagement =
        soapFactory.newDocumentManagement("token");
    adaptor.doDocument(documentManagement, testDocId,
        documentNode, request, response);

    assertNull(responseMock.contentType);
    assertEquals("",
        responseMock.outputStream.toString(UTF_8.name()));
  }

  @Test
  public void testDoDocumentNotModified() throws IOException {
    XMLGregorianCalendar fileModifyDate =
        getXmlGregorianCalendar(2015, 6, 6, 12, 12, 12);
    Date lastAccessTime = new Date(
        new GregorianCalendar(2016, 6, 6, 12, 12, 12).getTimeInMillis());;

    DocId docId = new DocId("2000/Document:3143");
    OpentextDocId testDocId = new OpentextDocId(docId);
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    NodeMock documentNode =
        new NodeMock(3143, "Title of Document", "Document");
    documentNode.setVersion(1, "text/plain",
        new GregorianCalendar(2015, 1, 3, 9, 42, 42));
    documentNode.getVersion().setFileModifyDate(fileModifyDate);
    soapFactory.documentManagementMock.addNode(documentNode);

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    RequestMock requestMock = new RequestMock(docId, lastAccessTime);
    Request request = Proxies.newProxyInstance(Request.class, requestMock);
    ResponseMock responseMock = new ResponseMock();
    Response response =
        Proxies.newProxyInstance(Response.class, responseMock);

    DocumentManagement documentManagement =
        soapFactory.newDocumentManagement("token");
    adaptor.doDocument(documentManagement, testDocId,
        documentNode, request, response);

    assertNull(responseMock.contentType);
    assertTrue(responseMock.noContent);
  }

  @Test
  public void testDocWithExcludedNodeType() throws IOException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();

    NodeMock documentNode =
        new NodeMock(3143, "Title of Document", "Alias");
    documentNode.setStartPointId(2000);
    documentNode.setPath(documentNode.getName());
    soapFactory.documentManagementMock.addNode(documentNode);

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.excludedNodeTypes", "Alias");
    adaptor.init(context);

    ResponseMock responseMock = new ResponseMock();
    Response response = Proxies.newProxyInstance(Response.class,
        responseMock);

    RequestMock requestMock = new RequestMock(
        new DocId("EnterpriseWS/Title+of+Document:3143"));
    Request request = Proxies.newProxyInstance(Request.class,
        requestMock);

    adaptor.getDocContent(request, response);
    assertTrue(responseMock.notFound());
  }

  @Test
  public void testPrimitiveValueIndexAllAttributes() {
    // Create the definition for the attribute.
    Attribute attribute = new PrimitiveAttribute();
    attribute.setKey("5432.1.2");
    attribute.setSearchable(false);
    Map<String, Attribute> attrDefinitionCache =
        new HashMap<String, Attribute>();
    attrDefinitionCache.put(attribute.getKey(), attribute);

    // Create the test attribute.
    StringValue stringValue = new StringValue();
    stringValue.setDescription("attribute name");
    stringValue.setKey("5432.1.2");
    stringValue.getValues().add("first value");
    stringValue.getValues().add("second value");
    stringValue.getValues().add("third value");

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.indexSearchableAttributesOnly", "false");
    adaptor.init(context);

    ResponseMock responseMock = new ResponseMock();
    adaptor.doPrimitiveValue(stringValue,
        Proxies.newProxyInstance(Response.class, responseMock),
        attrDefinitionCache, null);

    Map<String, List<String>> metadata = responseMock.getMetadata();
    List<String> values = metadata.get("attribute name");
    assertNotNull(values);
    assertEquals(
        Lists.newArrayList("first value", "second value", "third value"),
        values);
  }

  @Test
  public void testPrimitiveValueIndexSearchableAttributes() {
    // Create the definition for the attribute.
    Attribute attribute = new Attribute();
    attribute.setKey("5432.1.1");
    attribute.setSearchable(false);
    Map<String, Attribute> attrDefinitionCache =
        new HashMap<String, Attribute>();
    attrDefinitionCache.put(attribute.getKey(), attribute);

    // Create the test attribute.
    StringValue stringValue = new StringValue();
    stringValue.setDescription("attribute name");
    stringValue.setKey("5432.1.1");
    stringValue.getValues().add("first value");
    stringValue.getValues().add("second value");
    stringValue.getValues().add("third value");

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.indexSearchableAttributesOnly", "true");
    adaptor.init(context);

    // When the attribute is not searchable, no metadata is returned.
    ResponseMock responseMock = new ResponseMock();
    adaptor.doPrimitiveValue(stringValue,
        Proxies.newProxyInstance(Response.class, responseMock),
        attrDefinitionCache, null);
    Map<String, List<String>> metadata = responseMock.getMetadata();
    List<String> values = metadata.get("attribute name");
    assertNull(values);

    // When the attribute is searchable, metadata is returned.
    attribute.setSearchable(true);
    responseMock = new ResponseMock();
    adaptor.doPrimitiveValue(stringValue,
        Proxies.newProxyInstance(Response.class, responseMock),
        attrDefinitionCache, null);
    metadata = responseMock.getMetadata();
    values = metadata.get("attribute name");
    assertNotNull(values);
  }

  @Test
  public void testPrimitiveValueUserAttributes() {
    // Create the definition for the attribute.
    Attribute attribute = new UserAttribute();
    attribute.setKey("5432.1.1");
    attribute.setSearchable(true);
    Map<String, Attribute> attrDefinitionCache =
        new HashMap<String, Attribute>();
    attrDefinitionCache.put(attribute.getKey(), attribute);

    // Create the test attribute.
    IntegerValue integerValue = new IntegerValue();
    integerValue.setDescription("user attribute name");
    integerValue.setKey("5432.1.1");
    integerValue.getValues().add(new Long(14985));
    // Include an id with no corresponding Member; API puts
    // null in the member list when a member can't be found.
    integerValue.getValues().add(new Long(14986));

    // Create the corresponding member.
    Member member = getMember(14985, "testuser1", "User");

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    ResponseMock responseMock = new ResponseMock();
    soapFactory.memberServiceMock.addMember(member);
    adaptor.doPrimitiveValue(integerValue,
        Proxies.newProxyInstance(Response.class, responseMock),
        attrDefinitionCache, soapFactory.newMemberService(null));

    Map<String, List<String>> metadata = responseMock.getMetadata();
    List<String> values = metadata.get("user attribute name");
    assertNotNull(values);
    assertEquals(Lists.newArrayList("testuser1"), values);
  }

  @Test
  public void testPrimitiveValueDateAttributes() {
    // Create the definition for the attribute.
    Attribute attribute = new UserAttribute();
    attribute.setKey("5432.1.1");
    attribute.setSearchable(true);
    Map<String, Attribute> attrDefinitionCache =
        new HashMap<String, Attribute>();
    attrDefinitionCache.put(attribute.getKey(), attribute);

    // Create the test attribute.
    DateValue dateValue = new DateValue();
    dateValue.setDescription("date attribute name");
    dateValue.setKey("5432.1.1");
    dateValue.getValues().add(
        getXmlGregorianCalendar(1998, 11, 12, 14, 15, 16));

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doPrimitiveValue(dateValue,
        Proxies.newProxyInstance(Response.class, responseMock),
        attrDefinitionCache, soapFactory.newMemberService(null));

    Map<String, List<String>> metadata = responseMock.getMetadata();
    List<String> values = metadata.get("date attribute name");
    assertNotNull(values);
    assertEquals(Lists.newArrayList("1998-12-12"), values);
  }

  @Test
  public void testTableValue() {
    // Create the definition for the attributes.
    Map<String, Attribute> attrDefinitionCache =
        new HashMap<String, Attribute>();
    SetAttribute setAttribute = new SetAttribute();
    Attribute attribute = new PrimitiveAttribute();
    attribute.setKey("5432.1.1");
    attribute.setSearchable(true);
    attrDefinitionCache.put(attribute.getKey(), attribute);
    setAttribute.getAttributes().add(attribute);
    attribute = new PrimitiveAttribute();
    attribute.setKey("5432.1.2");
    attribute.setSearchable(true);
    attrDefinitionCache.put(attribute.getKey(), attribute);
    setAttribute.getAttributes().add(attribute);

    // Create the test attributes.
    StringValue stringValue = new StringValue();
    stringValue.setDescription("string attribute name");
    stringValue.setKey("5432.1.1");
    stringValue.getValues().add("first value");
    stringValue.getValues().add("second value");
    stringValue.getValues().add("third value");
    BooleanValue booleanValue = new BooleanValue();
    booleanValue.setDescription("boolean attribute name");
    booleanValue.setKey("5432.1.2");
    booleanValue.getValues().add(Boolean.TRUE);
    RowValue rowValue = new RowValue();
    rowValue.getValues().add(stringValue);
    rowValue.getValues().add(booleanValue);
    TableValue tableValue = new TableValue();
    tableValue.getValues().add(rowValue);

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doTableValue(tableValue,
        Proxies.newProxyInstance(Response.class, responseMock),
        attrDefinitionCache, null);

    Map<String, List<String>> metadata = responseMock.getMetadata();
    List<String> values = metadata.get("string attribute name");
    assertNotNull(values);
    assertEquals(
        Lists.newArrayList("first value", "second value", "third value"),
        values);
    values = metadata.get("boolean attribute name");
    assertNotNull(values);
    assertEquals(Lists.newArrayList("true"), values);
  }

  @Test
  public void testDoCategories() {
    // Create the definition for the category+attributes.
    AttributeGroupDefinition categoryDefinition =
        new AttributeGroupDefinition();
    categoryDefinition.setID(5432);
    categoryDefinition.setKey("5432.1");
    PrimitiveAttribute attribute = new PrimitiveAttribute();
    attribute.setKey("5432.1.1");
    attribute.setSearchable(true);
    categoryDefinition.getAttributes().add(attribute);
    attribute = new PrimitiveAttribute();
    attribute.setKey("5432.1.2");
    attribute.setSearchable(true);
    categoryDefinition.getAttributes().add(attribute);

    // Create the test attributes (metadata).
    StringValue stringValue = new StringValue();
    stringValue.setDescription("string attribute name");
    stringValue.setKey("5432.1.1");
    stringValue.getValues().add("first value");
    stringValue.getValues().add("second value");
    stringValue.getValues().add("third value");
    BooleanValue booleanValue = new BooleanValue();
    booleanValue.setDescription("boolean attribute name");
    booleanValue.setKey("5432.1.2");
    booleanValue.getValues().add(Boolean.TRUE);
    AttributeGroup attributeGroup = new AttributeGroup();
    attributeGroup.setType("Category");
    attributeGroup.setKey("5432.1");
    attributeGroup.getValues().add(stringValue);
    attributeGroup.getValues().add(booleanValue);
    Metadata metadata = new Metadata();
    metadata.getAttributeGroups().add(attributeGroup);

    // Set up the adaptor instance with the test data.
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    NodeMock documentNode =
        new NodeMock(3143, "Title of Document", "Document");
    documentNode.setMetadata(metadata);
    soapFactory.documentManagementMock.addNode(documentNode);
    soapFactory.documentManagementMock.addCategoryDefinition(
        categoryDefinition);

    ResponseMock responseMock = new ResponseMock();
    adaptor.doCategories(soapFactory.newDocumentManagement("token"),
        documentNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    Map<String, List<String>> responseMetadata = responseMock.getMetadata();
    List<String> values = responseMetadata.get("string attribute name");
    assertNotNull(values);
    assertEquals(
        Lists.newArrayList("first value", "second value", "third value"),
        values);
    values = responseMetadata.get("boolean attribute name");
    assertNotNull(values);
    assertEquals(Lists.newArrayList("true"), values);
  }

  @Test
  public void testShouldIndexNonCategory() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    AttributeGroup attributeGroup = new AttributeGroup();
    attributeGroup.setType("NotACategory");
    assertFalse(adaptor.shouldIndex(attributeGroup));
  }

  @Test
  public void testShouldIndexNoCategoryId() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    AttributeGroup attributeGroup = new AttributeGroup();
    attributeGroup.setType("Category");
    attributeGroup.setKey("random key");
    assertFalse(adaptor.shouldIndex(attributeGroup));
  }

  @Test
  public void testShouldIndexInIncludeList() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.includedCategories", "12345, 23456");
    adaptor.init(context);

    AttributeGroup attributeGroup = new AttributeGroup();
    attributeGroup.setType("Category");
    attributeGroup.setKey("12345.3");
    assertTrue(adaptor.shouldIndex(attributeGroup));
  }

  @Test
  public void testShouldIndexNotInIncludeList() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.includedCategories", "12345, 23456");
    adaptor.init(context);

    AttributeGroup attributeGroup = new AttributeGroup();
    attributeGroup.setType("Category");
    attributeGroup.setKey("92345.3");
    assertFalse(adaptor.shouldIndex(attributeGroup));
  }

  @Test
  public void testShouldIndexInExcludeList() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.excludedCategories", "12345, 23456");
    adaptor.init(context);

    AttributeGroup attributeGroup = new AttributeGroup();
    attributeGroup.setType("Category");
    attributeGroup.setKey("12345.3");
    assertFalse(adaptor.shouldIndex(attributeGroup));
  }

  @Test
  public void testShouldIndexNotInExcludeList() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.excludedCategories", "12345, 23456");
    adaptor.init(context);

    AttributeGroup attributeGroup = new AttributeGroup();
    attributeGroup.setType("Category");
    attributeGroup.setKey("82345.3");
    assertTrue(adaptor.shouldIndex(attributeGroup));
  }

  @Test
  public void testShouldIndexInBothIncludeAndExcludeList() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.includedCategories", "12345, 23456");
    config.overrideKey("opentext.excludedCategories", "12345, 23456");
    adaptor.init(context);

    AttributeGroup attributeGroup = new AttributeGroup();
    attributeGroup.setType("Category");
    attributeGroup.setKey("12345.3");
    assertFalse(adaptor.shouldIndex(attributeGroup));
  }

  @Test
  public void testIncludeCategoryName() {
    AttributeGroupDefinition categoryDefinition =
        new AttributeGroupDefinition();
    categoryDefinition.setID(82345);
    categoryDefinition.setKey("82345.3");

    AttributeGroup attributeGroup = new AttributeGroup();
    attributeGroup.setType("Category");
    attributeGroup.setKey("82345.3");
    attributeGroup.setDisplayName("Category Display Name");
    Metadata metadata = new Metadata();
    metadata.getAttributeGroups().add(attributeGroup);

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.indexCategoryNames", "true");
    adaptor.init(context);

    NodeMock documentNode =
        new NodeMock(3143, "Title of Document", "Document");
    documentNode.setMetadata(metadata);
    soapFactory.documentManagementMock.addNode(documentNode);
    soapFactory.documentManagementMock.addCategoryDefinition(
        categoryDefinition);

    ResponseMock responseMock = new ResponseMock();
    adaptor.doCategories(soapFactory.newDocumentManagement("token"),
        documentNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    Map<String, List<String>> responseMetadata = responseMock.getMetadata();
    assertEquals(
        "Category Display Name", responseMetadata.get("Category").get(0));
  }

  @Test
  public void testGetIncludedNodeFeatures() {
    Map<String, String> configuredFeatures = new HashMap<String, String>();
    configuredFeatures.put("NamedType", "Feature1, Feature2");
    configuredFeatures.put("124", "OtherFeature");

    Map<String, List<String>> includedFeatures =
        OpentextAdaptor.getIncludedNodeFeatures(configuredFeatures, ",");
    assertEquals(2, includedFeatures.size());
    assertEquals(Lists.newArrayList("Feature1", "Feature2"),
        includedFeatures.get("NamedType"));
    assertEquals(Lists.newArrayList("OtherFeature"),
        includedFeatures.get("GenericNode:124"));
  }

  @Test
  public void testGetIncludedNodeFeaturesEmpty() {
    Map<String, String> configuredFeatures = new HashMap<String, String>();

    Map<String, List<String>> includedFeatures =
        OpentextAdaptor.getIncludedNodeFeatures(configuredFeatures, ",");
    assertEquals(0, includedFeatures.size());
  }

  @Test
  public void testFixTypeKeys() {
    Map<String, String> original = new HashMap<String, String>();
    original.put("Document", "document value");
    original.put("123", "123 value");
    original.put("GenericNode:456", "456 value");
    Map<String, String> result = OpentextAdaptor.fixTypeKeys(original);
    assertEquals(original.size(), result.size());
    assertNull(result.get("123"));
    assertEquals("123 value", result.get("GenericNode:123"));
    assertEquals("document value", result.get("Document"));
    assertEquals("456 value", result.get("GenericNode:456"));
  }

  @Test
  public void testGetCanonicalType() {
    assertNull(OpentextAdaptor.getCanonicalType(null));
    assertEquals("TextType", OpentextAdaptor.getCanonicalType("TextType"));
    assertEquals("GenericNode:123",
        OpentextAdaptor.getCanonicalType("123"));
    assertEquals("GenericNode:123",
        OpentextAdaptor.getCanonicalType("GenericNode:123"));
  }

  @Test
  public void testDoNodeFeatures() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.addKey("opentext.includedNodeFeatures.NodeType",
        "Feature1,Feature2");
    adaptor.init(context);

    NodeMock node = new NodeMock(3143, "Node Name", "NodeType");
    NodeFeature feature = new NodeFeature();
    feature.setName("Feature1");
    feature.setBooleanValue(true);
    feature.setType("Boolean");
    node.getFeatures().add(feature);

    ResponseMock responseMock = new ResponseMock();
    adaptor.doNodeFeatures(
        node, Proxies.newProxyInstance(Response.class, responseMock));
    Map<String, List<String>> responseMetadata = responseMock.getMetadata();
    assertEquals(1, responseMetadata.size());
    assertEquals("true", responseMetadata.get("Feature1").get(0));
    assertNull(responseMetadata.get("Feature2"));
  }

  @Test
  public void testDoNodeFeaturesWithDate() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.addKey("opentext.includedNodeFeatures.NodeType",
        "Feature1,Feature2");
    adaptor.init(context);

    NodeMock node = new NodeMock(3143, "Node Name", "NodeType");
    NodeFeature feature = new NodeFeature();
    feature.setName("Feature1");
    feature.setDateValue(getXmlGregorianCalendar(2011, 01, 01, 01, 01, 01));
    feature.setType("Date");
    node.getFeatures().add(feature);

    ResponseMock responseMock = new ResponseMock();
    adaptor.doNodeFeatures(
        node, Proxies.newProxyInstance(Response.class, responseMock));
    Map<String, List<String>> responseMetadata = responseMock.getMetadata();
    assertEquals(1, responseMetadata.size());
    assertEquals("2011-02-01", responseMetadata.get("Feature1").get(0));
    assertNull(responseMetadata.get("Feature2"));
  }

  @Test
  public void testDoNodeProperties() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    Member member = getMember(14985, "testuser1", "User");
    soapFactory.memberServiceMock.addMember(member);

    NodeMock node = new NodeMock(54678, "Node Name");
    node.setComment("Node comment");
    node.setCreateDate(2012, 3, 1, 4, 34, 21);
    node.setModifyDate(2013, 3, 1, 4, 34, 21);
    node.setCreatedBy(new Long(14985));
    node.setType("NodeType");
    node.setDisplayType("Node Display Type");
    node.setVolumeID(new Long(-321));
    NodeVersionInfo versionInfo = new NodeVersionInfo();
    versionInfo.setMimeType("test/mime-type");
    node.setVersionInfo(versionInfo);

    ResponseMock responseMock = new ResponseMock();
    adaptor.doNodeProperties(soapFactory.newDocumentManagement("token"),
        node, Proxies.newProxyInstance(Response.class, responseMock));
    Map<String, List<String>> responseMetadata = responseMock.getMetadata();

    assertEquals("54678", responseMetadata.get("ID").get(0));
    assertEquals("Node Name", responseMetadata.get("Name").get(0));
    assertEquals("Node comment", responseMetadata.get("Comment").get(0));
    assertEquals("2012-04-01", responseMetadata.get("CreateDate").get(0));
    assertEquals("2013-04-01", responseMetadata.get("ModifyDate").get(0));
    assertEquals("testuser1", responseMetadata.get("CreatedBy").get(0));
    assertEquals("NodeType", responseMetadata.get("SubType").get(0));
    assertEquals(
        "Node Display Type", responseMetadata.get("DisplayType").get(0));
    assertEquals("-321", responseMetadata.get("VolumeID").get(0));
    assertEquals("test/mime-type", responseMetadata.get("MimeType").get(0));
    assertEquals(node.getModifyDate().toGregorianCalendar().getTime(),
        responseMock.lastModified);
  }

  @Test
  public void testDoNodePropertiesCustomDateFormat() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.metadataDateFormat", "MM dd, yyyy");
    adaptor.init(context);

    NodeMock node = new NodeMock(54678, "Node Name", "NodeType");
    node.setCreateDate(2012, 3, 1, 4, 34, 21);
    node.setModifyDate(2013, 3, 1, 4, 34, 21);

    ResponseMock responseMock = new ResponseMock();
    adaptor.doNodeProperties(soapFactory.newDocumentManagement("token"),
        node, Proxies.newProxyInstance(Response.class, responseMock));
    Map<String, List<String>> responseMetadata = responseMock.getMetadata();

    assertEquals("04 01, 2012", responseMetadata.get("CreateDate").get(0));
    assertEquals("04 01, 2013", responseMetadata.get("ModifyDate").get(0));
  }

  @Test
  public void testDoNode() throws IOException {
    NodeMock node = new NodeMock(432, "Test Node");
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doNode(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("432:432")),
        node,
        Proxies.newProxyInstance(Response.class, responseMock));
    String expected = "<!DOCTYPE html>\n"
        + "<html><head><title>Test Node</title></head>"
        + "<body><h1>Test Node</h1>"
        + "</body></html>";
    assertEquals(expected,
        responseMock.outputStream.toString("UTF-8"));
  }

  @Test
  public void testDoCollection() throws IOException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    NodeMock containerNode =
        new NodeMock(3000, "CollectionName", "Collection");
    containerNode.setStartPointId(2000);
    containerNode.setPath("CollectionName");
    soapFactory.documentManagementMock.addNode(containerNode);
    for (int i = 1; i <= 3; i++) {
      NodeMock testNode = new NodeMock(4000 + i, "Document " + i);
      testNode.setStartPointId(2000);
      testNode.setPath("CollectionName", "Document " + i);
      soapFactory.documentManagementMock.addNode(testNode);
    }
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doCollection(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/CollectionName:3000")),
        containerNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    String expected = "<!DOCTYPE html>\n"
        + "<html><head><title>CollectionName</title></head>"
        + "<body><h1>CollectionName</h1>"
        + "<p>Document 1</p>"
        + "<p>Document 2</p>"
        + "<p>Document 3</p>"
        + "</body></html>";
    assertEquals(expected,
        responseMock.outputStream.toString("UTF-8"));
  }

  @Test
  public void testDoCollectionErrorGettingContents() throws IOException {
    class DocumentManagementMockError extends DocumentManagementMock {
      public List<Node> listNodes(long containerNodeId, boolean partialData) {
        throw getSoapFaultException("error retrieving child nodes",
            "uri", "local", "prefix");
      }
    };
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    NodeMock containerNode =
        new NodeMock(3000, "CollectionName", "Collection");
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doCollection(
        soapFactory.newDocumentManagement(new DocumentManagementMockError()),
        new OpentextDocId(new DocId("2000/Folder:3000")),
        containerNode,
        Proxies.newProxyInstance(Response.class, responseMock));

    String expected = "<!DOCTYPE html>\n"
        + "<html><head><title>CollectionName</title></head>"
        + "<body><h1>CollectionName</h1>"
        + "</body></html>";
    assertEquals(expected,
        responseMock.outputStream.toString("UTF-8"));
  }

  @Test
  public void testDoMilestone() throws IOException {
    MilestoneInfo milestoneInfo = new MilestoneInfo();
    milestoneInfo.setID(3000);
    milestoneInfo.setActualDate(
        getXmlGregorianCalendar(2011, 01, 01, 01, 01, 01));
    milestoneInfo.setDuration(45);
    milestoneInfo.setNumActive(2);
    milestoneInfo.setNumCancelled(0);
    milestoneInfo.setNumCompleted(3);
    milestoneInfo.setNumInprocess(5);
    milestoneInfo.setNumIssue(2);
    milestoneInfo.setNumLate(0);
    milestoneInfo.setNumOnHold(4);
    milestoneInfo.setNumPending(7);
    milestoneInfo.setNumTasks(8);
    milestoneInfo.setOriginalTargetDate(
        getXmlGregorianCalendar(2012, 01, 01, 01, 01, 01));
    milestoneInfo.setPercentCancelled(15.0);
    milestoneInfo.setPercentComplete(55.0);
    milestoneInfo.setPercentInprocess(32.0);
    milestoneInfo.setPercentIssue(11.0);
    milestoneInfo.setPercentLate(4.0);
    milestoneInfo.setPercentOnHold(45.0);
    milestoneInfo.setPercentPending(13.0);
    milestoneInfo.setResources(99);
    milestoneInfo.setTargetDate(
        getXmlGregorianCalendar(2013, 01, 01, 01, 01, 01));
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    NodeMock milestoneNode =
        new NodeMock(3000, "TestMilestone", "Milestone");
    milestoneNode.setStartPointId(2000);
    milestoneNode.setPath("TestMilestone");
    for (int i = 1; i <= 3; i++) {
      NodeMock testNode = new NodeMock(4000 + i, "Document " + i);
      testNode.setStartPointId(2000);
      testNode.setPath("TestMilestone", "Document " + i);
      soapFactory.documentManagementMock.addNode(testNode);
    }
    soapFactory.documentManagementMock.addNode(milestoneNode);
    soapFactory.collaborationMock.addMilestoneInfo(milestoneInfo);
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doMilestone(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/TestMilestone:3000")),
        milestoneNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    String expected = "<!DOCTYPE html>\n"
        + "<html><head><title>TestMilestone</title></head>"
        + "<body><h1>TestMilestone</h1>"
        + "</body></html>";
    assertEquals(expected,
        responseMock.outputStream.toString("UTF-8"));
    Map<String, List<String>> responseMetadata = responseMock.getMetadata();
    assertEquals("2011-02-01", responseMetadata.get("ActualDate").get(0));
    assertEquals("45", responseMetadata.get("Duration").get(0));
    assertEquals("2", responseMetadata.get("NumActive").get(0));
    assertEquals("0", responseMetadata.get("NumCancelled").get(0));
    assertEquals("3", responseMetadata.get("NumCompleted").get(0));
    assertEquals("5", responseMetadata.get("NumInProcess").get(0));
    assertEquals("2", responseMetadata.get("NumIssue").get(0));
    assertEquals("0", responseMetadata.get("NumLate").get(0));
    assertEquals("4", responseMetadata.get("NumOnHold").get(0));
    assertEquals("7", responseMetadata.get("NumPending").get(0));
    assertEquals("8", responseMetadata.get("NumTasks").get(0));
    assertEquals("2012-02-01",
        responseMetadata.get("OriginalTargetDate").get(0));
    assertEquals("15.0", responseMetadata.get("PercentCancelled").get(0));
    assertEquals("55.0", responseMetadata.get("PercentComplete").get(0));
    assertEquals("32.0", responseMetadata.get("PercentInProcess").get(0));
    assertEquals("11.0", responseMetadata.get("PercentIssue").get(0));
    assertEquals("4.0", responseMetadata.get("PercentLate").get(0));
    assertEquals("45.0", responseMetadata.get("PercentOnHold").get(0));
    assertEquals("13.0", responseMetadata.get("PercentPending").get(0));
    assertEquals("99", responseMetadata.get("Resources").get(0));
    assertEquals("2013-02-01", responseMetadata.get("TargetDate").get(0));
    List<String> anchors = responseMock.getAnchors();
    assertEquals(3, anchors.size());
    assertEquals(
        Lists.newArrayList("Document 1", "Document 2", "Document 3"),
        anchors);
  }

  @Test
  public void testDoNews() throws IOException {
    NewsInfo newsInfo = new NewsInfo();
    newsInfo.setCreatedBy(new Long(1001));
    newsInfo.setEffectiveDate(
        getXmlGregorianCalendar(2013, 01, 01, 01, 01, 01));
    newsInfo.setExpirationDate(
        getXmlGregorianCalendar(2013, 01, 11, 01, 01, 01));
    newsInfo.setHeadline("This Is The Headline");
    newsInfo.setID(12345);
    newsInfo.setName("NewsInfoName");
    newsInfo.setStory("This is the news story.");
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    NodeMock newsNode = new NodeMock(12345, "NewsInfoName", "News");
    newsNode.setStartPointId(2000);
    newsNode.setPath("NewsInfoName");
    for (int i = 1; i <= 3; i++) {
      NodeMock testNode = new NodeMock(4000 + i, "Document " + i);
      testNode.setStartPointId(2000);
      testNode.setPath("NewsInfoName", "Document " + i);
      soapFactory.documentManagementMock.addNode(testNode);
    }
    soapFactory.documentManagementMock.addNode(newsNode);
    soapFactory.collaborationMock.addNewsInfo(newsInfo);
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doNews(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/News+Info+Name:12345")),
        newsNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    String expected = "<!DOCTYPE html>\n"
        + "<html><head><title>NewsInfoName</title></head>"
        + "<body><h1>This Is The Headline</h1>"
        + "<p>This is the news story.</p>"
        + "</body></html>";
    assertEquals(expected,
        responseMock.outputStream.toString("UTF-8"));
    Map<String, List<String>> responseMetadata = responseMock.getMetadata();
    assertEquals("2013-02-01", responseMetadata.get("EffectiveDate").get(0));
    assertEquals("2013-02-11", responseMetadata.get("ExpirationDate").get(0));
    List<String> anchors = responseMock.getAnchors();
    assertEquals(3, anchors.size());
    assertEquals(
        Lists.newArrayList("Document 1", "Document 2", "Document 3"),
        anchors);
  }

  @Test
  public void testDoNewsErrorGettingAttachments() throws IOException {
    class CollaborationMockError extends CollaborationMock {
      public NewsInfo getNews(long id) {
        throw getSoapFaultException("error retrieving news info",
            "uri", "local", "prefix");
      }
    };
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    soapFactory.collaborationMock = new CollaborationMockError();
    NodeMock newsNode = new NodeMock(12345, "NewsName", "News");
    newsNode.setStartPointId(2000);
    newsNode.setPath("NewsName");
    for (int i = 1; i <= 3; i++) {
      NodeMock testNode = new NodeMock(4000 + i, "Document " + i);
      testNode.setStartPointId(2000);
      testNode.setPath("NewsName", "Document " + i);
      soapFactory.documentManagementMock.addNode(testNode);
    }
    soapFactory.documentManagementMock.addNode(newsNode);
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doNews(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/News+Name:12345")),
        newsNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    String expected = "<!DOCTYPE html>\n"
        + "<html><head><title>Folder 2000/News+Name:12345</title>"
        + "</head><body><h1>Folder 2000/News+Name:12345</h1>"
        + "<li><a href=\"2000/News+Name/Document+1:4001\">Document 1</a></li>"
        + "<li><a href=\"2000/News+Name/Document+2:4002\">Document 2</a></li>"
        + "<li><a href=\"2000/News+Name/Document+3:4003\">Document 3</a></li>"
        + "</body></html>";
    assertEquals(expected,
        responseMock.outputStream.toString("UTF-8"));
  }

  @Test
  public void testDoProject() throws IOException {
    ProjectInfo projectInfo = new ProjectInfo();
    projectInfo.setCreatedBy(new Long(1001));
    projectInfo.setGoals("These are the goals.");
    projectInfo.setID(3000);
    projectInfo.setInitiatives("These are the initiatives.");
    projectInfo.setMission("This is the mission.");
    projectInfo.setName("ProjectName");
    projectInfo.setObjectives("These are the objectives.");
    projectInfo.setPublicAccess(false);
    projectInfo.setStartDate(
        getXmlGregorianCalendar(2013, 01, 01, 01, 01, 01));
    projectInfo.setStatus(ProjectStatus.PENDING);
    projectInfo.setTargetDate(
        getXmlGregorianCalendar(2014, 01, 01, 01, 01, 01));
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    NodeMock projectNode = new NodeMock(3000, "ProjectName", "Project");
    projectNode.setStartPointId(2000);
    projectNode.setPath("ProjectName");
    for (int i = 1; i <= 3; i++) {
      NodeMock testNode = new NodeMock(4000 + i, "Document " + i);
      testNode.setStartPointId(2000);
      testNode.setPath("ProjectName", "Document " + i);
      soapFactory.documentManagementMock.addNode(testNode);
    }
    soapFactory.documentManagementMock.addNode(projectNode);
    soapFactory.collaborationMock.addProjectInfo(projectInfo);
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doProject(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/Project+Name:3000")),
        projectNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    String expected = "<!DOCTYPE html>\n"
        + "<html><head><title>ProjectName</title></head>"
        + "<body><h1>ProjectName</h1>"
        + "</body></html>";
    assertEquals(expected,
        responseMock.outputStream.toString("UTF-8"));
    Map<String, List<String>> responseMetadata = responseMock.getMetadata();
    assertEquals("2013-02-01", responseMetadata.get("StartDate").get(0));
    assertEquals("2014-02-01", responseMetadata.get("TargetDate").get(0));
    assertEquals("These are the goals.",
        responseMetadata.get("Goals").get(0));
    assertEquals("These are the initiatives.",
        responseMetadata.get("Initiatives").get(0));
    assertEquals("This is the mission.",
        responseMetadata.get("Mission").get(0));
    assertEquals("These are the objectives.",
        responseMetadata.get("Objectives").get(0));
    assertEquals("PENDING", responseMetadata.get("Status").get(0));
    List<String> anchors = responseMock.getAnchors();
    assertEquals(3, anchors.size());
    assertEquals(
        Lists.newArrayList("Document 1", "Document 2", "Document 3"),
        anchors);
  }

  @Test
  public void testDoProjectErrorGettingContents() throws IOException {
    class CollaborationMockError extends CollaborationMock {
      public ProjectInfo getProject(long id) {
        throw getSoapFaultException("error retrieving project info",
            "uri", "local", "prefix");
      }
    };
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    soapFactory.collaborationMock = new CollaborationMockError();
    NodeMock projectNode = new NodeMock(3000, "ProjectName", "Project");
    projectNode.setStartPointId(2000);
    projectNode.setPath("ProjectName");
    for (int i = 1; i <= 3; i++) {
      NodeMock testNode = new NodeMock(4000 + i, "Document " + i);
      testNode.setStartPointId(2000);
      testNode.setPath("ProjectName", "Document " + i);
      soapFactory.documentManagementMock.addNode(testNode);
    }
    soapFactory.documentManagementMock.addNode(projectNode);
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doProject(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/ProjectName:3000")),
        projectNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    String expected = "<!DOCTYPE html>\n"
        + "<html><head><title>Folder 2000/ProjectName:3000</title>"
        + "</head><body><h1>Folder 2000/ProjectName:3000</h1>"
        + "<li><a href=\"2000/ProjectName/Document+1:4001\">"
        + "Document 1</a></li>"
        + "<li><a href=\"2000/ProjectName/Document+2:4002\">"
        + "Document 2</a></li>"
        + "<li><a href=\"2000/ProjectName/Document+3:4003\">"
        + "Document 3</a></li>"
        + "</body></html>";
    assertEquals(expected,
        responseMock.outputStream.toString("UTF-8"));
  }

  @Test
  public void testDoTopicReply() throws IOException {
    DiscussionItem discussionItem = new DiscussionItem();
    discussionItem.setContent("Discussion item content.");
    discussionItem.setID(3000);
    discussionItem.setOrdering(3);
    discussionItem.setPostedBy(1001);
    discussionItem.setPostedDate(
        getXmlGregorianCalendar(2013, 01, 01, 01, 01, 01));
    discussionItem.setSubject("Discussion item subject.");
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    NodeMock discussionNode =
        new NodeMock(3000, "Discussion item subject.", "Topic");
    discussionNode.setStartPointId(2000);
    discussionNode.setPath("Discussion item subject.");
    for (int i = 1; i <= 3; i++) {
      NodeMock testNode = new NodeMock(4000 + i, "Document " + i);
      testNode.setStartPointId(2000);
      testNode.setPath("Discussion item subject.", "Document " + i);
      soapFactory.documentManagementMock.addNode(testNode);
    }
    // Create the corresponding member.
    Member member = getMember(1001, "testuser1", "User");
    soapFactory.memberServiceMock.addMember(member);
    soapFactory.documentManagementMock.addNode(discussionNode);
    soapFactory.collaborationMock.addDiscussionItem(discussionItem);
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doTopicReply(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/Project+Name:3000")),
        discussionNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    String expected = "<!DOCTYPE html>\n"
        + "<html><head><title>Discussion item subject.</title></head>"
        + "<body><h1>Discussion item subject.</h1>"
        + "<p>Discussion item content.</p>"
        + "</body></html>";
    assertEquals(expected,
        responseMock.outputStream.toString("UTF-8"));
    Map<String, List<String>> responseMetadata = responseMock.getMetadata();
    assertEquals("2013-02-01", responseMetadata.get("PostedDate").get(0));
    assertEquals("testuser1", responseMetadata.get("PostedBy").get(0));
    List<String> anchors = responseMock.getAnchors();
    assertEquals(3, anchors.size());
    assertEquals(
        Lists.newArrayList("Document 1", "Document 2", "Document 3"),
        anchors);
  }

  @Test
  public void testDoTopicReplyErrorGettingContents() throws IOException {
    class CollaborationMockError extends CollaborationMock {
      public DiscussionItem getTopicReply(long id) {
        throw getSoapFaultException("error retrieving discussion item",
            "uri", "local", "prefix");
      }
    };
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    NodeMock discussionNode =
        new NodeMock(3000, "Discussion item subject.", "Topic");
    discussionNode.setStartPointId(2000);
    discussionNode.setPath("Discussion item subject.");
    for (int i = 1; i <= 3; i++) {
      NodeMock testNode = new NodeMock(4000 + i, "Document " + i);
      testNode.setStartPointId(2000);
      testNode.setPath("Discussion item subject.", "Document " + i);
      soapFactory.documentManagementMock.addNode(testNode);
    }
    soapFactory.documentManagementMock.addNode(discussionNode);
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doTopicReply(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/Discussion+item+subject.:3000")),
        discussionNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    String expected = "<!DOCTYPE html>\n"
        + "<html><head><title>Folder 2000/Discussion+item+subject.:3000"
        + "</title>"
        + "</head><body><h1>Folder 2000/Discussion+item+subject.:3000</h1>"
        + "<li><a href=\"2000/Discussion+item+subject./Document+1:4001\">"
        + "Document 1</a></li>"
        + "<li><a href=\"2000/Discussion+item+subject./Document+2:4002\">"
        + "Document 2</a></li>"
        + "<li><a href=\"2000/Discussion+item+subject./Document+3:4003\">"
        + "Document 3</a></li>"
        + "</body></html>";
    assertEquals(expected,
        responseMock.outputStream.toString("UTF-8"));
  }

  @Test
  public void testDoTask() throws IOException {
    TaskInfo taskInfo = new TaskInfo();
    taskInfo.setAssignedTo(new Long(1001));
    taskInfo.setComments("These are the comments.");
    taskInfo.setCompletionDate(
        getXmlGregorianCalendar(2013, 01, 01, 01, 01, 01));
    taskInfo.setDateAssigned(
        getXmlGregorianCalendar(2012, 01, 01, 01, 01, 01));
    taskInfo.setDueDate(
        getXmlGregorianCalendar(2014, 01, 01, 01, 01, 01));
    taskInfo.setID(3000);
    taskInfo.setInstructions("These are the instructions.");
    taskInfo.setMilestone(54678);
    taskInfo.setName("TaskName");
    taskInfo.setPriority(TaskPriority.LOW);
    taskInfo.setStartDate(
        getXmlGregorianCalendar(2012, 01, 01, 01, 01, 01));
    taskInfo.setStatus(TaskStatus.PENDING);
    Member member = getMember(1001, "testuser1", "User");
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    soapFactory.memberServiceMock.addMember(member);
    NodeMock taskNode = new NodeMock(3000, "TaskName", "Task");
    taskNode.setStartPointId(2000);
    taskNode.setPath("TaskName");
    for (int i = 1; i <= 3; i++) {
      NodeMock testNode = new NodeMock(4000 + i, "Document " + i);
      testNode.setStartPointId(2000);
      testNode.setPath("TaskName", "Document " + i);
      soapFactory.documentManagementMock.addNode(testNode);
    }
    soapFactory.documentManagementMock.addNode(taskNode);
    soapFactory.collaborationMock.addTaskInfo(taskInfo);
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doTask(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/TaskName:3000")),
        taskNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    String expected = "<!DOCTYPE html>\n"
        + "<html><head><title>TaskName</title></head>"
        + "<body><h1>TaskName</h1>"
        + "<p>These are the comments.</p>"
        + "<p>These are the instructions.</p>"
        + "</body></html>";
    assertEquals(expected,
        responseMock.outputStream.toString("UTF-8"));
    Map<String, List<String>> responseMetadata = responseMock.getMetadata();
    assertEquals("testuser1", responseMetadata.get("AssignedTo").get(0));
    assertEquals("2013-02-01", responseMetadata.get("CompletionDate").get(0));
    assertEquals("2012-02-01", responseMetadata.get("DateAssigned").get(0));
    assertEquals("2014-02-01", responseMetadata.get("DueDate").get(0));
    assertEquals("2012-02-01", responseMetadata.get("StartDate").get(0));
    assertEquals("LOW", responseMetadata.get("Priority").get(0));
    assertEquals("PENDING", responseMetadata.get("Status").get(0));
    List<String> anchors = responseMock.getAnchors();
    assertEquals(3, anchors.size());
    assertEquals(
        Lists.newArrayList("Document 1", "Document 2", "Document 3"),
        anchors);
  }

  @Test
  public void testDoTaskErrorGettingContents() throws IOException {
    class CollaborationMockError extends CollaborationMock {
      public TaskInfo getTask(long id) {
        throw getSoapFaultException("error retrieving task info",
            "uri", "local", "prefix");
      }
    };
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    soapFactory.collaborationMock = new CollaborationMockError();
    NodeMock taskNode = new NodeMock(3000, "TaskName", "Task");
    taskNode.setStartPointId(2000);
    taskNode.setPath("TaskName");
    for (int i = 1; i <= 3; i++) {
      NodeMock testNode = new NodeMock(4000 + i, "Document " + i);
      testNode.setStartPointId(2000);
      testNode.setPath("TaskName", "Document " + i);
      soapFactory.documentManagementMock.addNode(testNode);
    }
    soapFactory.documentManagementMock.addNode(taskNode);
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doTask(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/TaskName:3000")),
        taskNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    String expected = "<!DOCTYPE html>\n"
        + "<html><head><title>Folder 2000/TaskName:3000</title>"
        + "</head><body><h1>Folder 2000/TaskName:3000</h1>"
        + "<li><a href=\"2000/TaskName/Document+1:4001\">Document 1</a></li>"
        + "<li><a href=\"2000/TaskName/Document+2:4002\">Document 2</a></li>"
        + "<li><a href=\"2000/TaskName/Document+3:4003\">Document 3</a></li>"
        + "</body></html>";
    assertEquals(expected,
        responseMock.outputStream.toString("UTF-8"));
  }

  @Test
  public void testAclOwnerRight() throws IOException {
    Member owner = getMember(1001, "testuser1", "User");
    NodeRights nodeRights = new NodeRights();
    nodeRights.setOwnerRight(getNodeRight(owner.getID(), "Owner"));
    NodeMock documentNode = new NodeMock(3000, "DocumentName", "Document");
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    soapFactory.documentManagementMock
        .setNodeRights(documentNode.getID(), nodeRights);
    soapFactory.memberServiceMock.addMember(owner);
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doAcl(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/DocumentName:3000")),
        documentNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    assertEquals(
        Sets.newHashSet(newUserPrincipal(owner.getName())),
        responseMock.getAcl().getPermits());
  }

  @Test
  public void testAclOwnerGroupRight() throws IOException {
    Member ownerGroup = getMember(1002, "DefaultGroup", "Group");
    NodeRights nodeRights = new NodeRights();
    nodeRights.setOwnerGroupRight(
        getNodeRight(ownerGroup.getID(), "OwnerGroup"));
    NodeMock documentNode = new NodeMock(3000, "DocumentName", "Document");
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    soapFactory.documentManagementMock
        .setNodeRights(documentNode.getID(), nodeRights);
    soapFactory.memberServiceMock.addMember(ownerGroup);
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doAcl(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/DocumentName:3000")),
        documentNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    assertEquals(
        Sets.newHashSet(newGroupPrincipal(ownerGroup.getName())),
        responseMock.getAcl().getPermits());
  }

  @Test
  public void testAclPublicRight() throws IOException {
    NodeRights nodeRights = new NodeRights();
    nodeRights.setPublicRight(getNodeRight(-1, "Public"));
    NodeMock documentNode = new NodeMock(3000, "DocumentName", "Document");
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    soapFactory.documentManagementMock
        .setNodeRights(documentNode.getID(), nodeRights);
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.publicAccessGroupEnabled", "true");
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doAcl(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/DocumentName:3000")),
        documentNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    assertEquals(
        Sets.newHashSet(newGroupPrincipal("[Public Access]")),
        responseMock.getAcl().getPermits());
  }

  @Test
  public void testAclPublicRightNotEnabled() throws IOException {
    thrown.expect(RuntimeException.class);
    thrown.expectMessage(
        "No ACL information for DocId(2000/DocumentName:3000)");

    NodeRights nodeRights = new NodeRights();
    nodeRights.setPublicRight(getNodeRight(-1, "Public"));
    NodeMock documentNode = new NodeMock(3000, "DocumentName", "Document");
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    soapFactory.documentManagementMock
        .setNodeRights(documentNode.getID(), nodeRights);
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.publicAccessGroupEnabled", "false");
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doAcl(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/DocumentName:3000")),
        documentNode,
        Proxies.newProxyInstance(Response.class, responseMock));
  }

  @Test
  public void testAclAclRights() throws IOException {
    Member aclUser = getMember(1001, "testuser1", "User");
    NodeRights nodeRights = new NodeRights();
    nodeRights.getACLRights().add(getNodeRight(aclUser.getID(), "ACL"));
    NodeMock documentNode = new NodeMock(3000, "DocumentName", "Document");
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    soapFactory.documentManagementMock
        .setNodeRights(documentNode.getID(), nodeRights);
    soapFactory.memberServiceMock.addMember(aclUser);
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doAcl(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/DocumentName:3000")),
        documentNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    assertEquals(
        Sets.newHashSet(newUserPrincipal(aclUser.getName())),
        responseMock.getAcl().getPermits());
  }

  /**
   * This test emulates an item within a project whose ACLs
   * reference the parent project's Members/Guests/Coordinators
   * groups.
   */
  @Test
  public void testAclProjectMember() throws IOException {
    Member owner = getMember(1001, "testuser1", "User");
    Member ownerGroup = getMember(1002, "DefaultGroup", "Group");
    Member guestUser = getMember(1003, "GuestUser", "User");
    Member projectCoordinators =
        getMember(54321, "Coordinators", "ProjectGroup");
    Member projectMembers = getMember(54322, "Members", "ProjectGroup");
    Member projectGuests = getMember(54323, "Guests", "ProjectGroup");
    Member projectGroup1 = getMember(98765, "Group1", "ProjectGroup");
    Member projectGroup2 = getMember(98767, "Group2", "ProjectGroup");
    NodeRights nodeRights = new NodeRights();
    nodeRights.setOwnerRight(getNodeRight(owner.getID(), "Owner"));
    nodeRights.setOwnerGroupRight(
        getNodeRight(ownerGroup.getID(), "OwnerGroup"));
    nodeRights.getACLRights().add(
        getNodeRight(projectGroup1.getID(), "ACL"));
    nodeRights.getACLRights().add(
        getNodeRight(projectGroup2.getID(), "ACL"));
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    soapFactory.memberServiceMock.addMember(owner);
    soapFactory.memberServiceMock.addMember(ownerGroup);
    soapFactory.memberServiceMock.addMember(guestUser);
    soapFactory.memberServiceMock.addMember(projectCoordinators);
    soapFactory.memberServiceMock.addMember(projectMembers);
    soapFactory.memberServiceMock.addMember(projectGuests);
    soapFactory.memberServiceMock.addMember(projectGroup1);
    soapFactory.memberServiceMock.addMember(projectGroup2);
    soapFactory.memberServiceMock.addMemberToGroup(
        projectCoordinators.getID(), owner);
    soapFactory.memberServiceMock.addMemberToGroup(
        projectMembers.getID(), ownerGroup);
    soapFactory.memberServiceMock.addMemberToGroup(
        projectGuests.getID(), guestUser);
    soapFactory.memberServiceMock.addMemberToGroup(
        projectGroup1.getID(), projectCoordinators);
    soapFactory.memberServiceMock.addMemberToGroup(
        projectGroup1.getID(), projectMembers);
    soapFactory.memberServiceMock.addMemberToGroup(
        projectGroup2.getID(), projectGuests);
    NodeMock documentNode = new NodeMock(3000, "DocumentName", "Document");
    soapFactory.documentManagementMock
        .setNodeRights(documentNode.getID(), nodeRights);
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doAcl(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/DocumentName:3000")),
        documentNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    assertEquals(
        Sets.newHashSet(newUserPrincipal(owner.getName()),
            newUserPrincipal(guestUser.getName()),
            newGroupPrincipal(ownerGroup.getName())),
        responseMock.getAcl().getPermits());;
  }

  @Test
  public void testAclNoPermissions() throws IOException {
    thrown.expect(RuntimeException.class);
    thrown.expectMessage(
        "No ACL information for DocId(2000/DocumentName:3000)");

    NodeRight nodeRight = getNodeRight(-1, "Public");
    nodeRight.getPermissions().setSeeContentsPermission(false);
    NodeRights nodeRights = new NodeRights();
    nodeRights.setPublicRight(nodeRight);
    NodeMock documentNode = new NodeMock(3000, "DocumentName", "Document");
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    soapFactory.documentManagementMock
        .setNodeRights(documentNode.getID(), nodeRights);
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doAcl(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/DocumentName:3000")),
        documentNode,
        Proxies.newProxyInstance(Response.class, responseMock));
  }

  @Test
  public void testAclInvalidAdminUser() throws IOException {
    thrown.expect(SOAPFaultException.class);
    thrown.expectMessage("Failed to authenticate as admin");

    class AuthenticationMockError extends AuthenticationMock {
      public String authenticateUser(String username, String password) {
        throw getSoapFaultException("Failed to authenticate as admin",
            "urn:Core.service.livelink.opentext.com",
            "Core.LoginFailed", "ns0");
      }
    };
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    NodeMock documentNode = new NodeMock(3000, "DocumentName", "Document");
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.adminUsername", "validuser");
    config.overrideKey("opentext.adminPassword", "validpassword");
    adaptor.init(context);
    // Replace authenticationMock after calling init so we don't
    // just get the error there; we want to trigger it in doAcl
    // to verify that the exception's rethrown.
    soapFactory.authenticationMock = new AuthenticationMockError();
    ResponseMock responseMock = new ResponseMock();
    adaptor.doAcl(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/DocumentName:3000")),
        documentNode,
        Proxies.newProxyInstance(Response.class, responseMock));
  }

  @Test
  public void testAclGetNodeRightsError() throws IOException {
    thrown.expect(SOAPFaultException.class);
    thrown.expectMessage("Failed to get node rights");

    class DocumentManagementMockError extends DocumentManagementMock {
      public NodeRights getNodeRights(long id) {
        throw getSoapFaultException("Failed to get node rights",
            "uri", "local", "ns0");
      }
    };
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    soapFactory.documentManagementMock = new DocumentManagementMockError();
    NodeMock documentNode = new NodeMock(3000, "DocumentName", "Document");
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doAcl(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/DocumentName:3000")),
        documentNode,
        Proxies.newProxyInstance(Response.class, responseMock));
  }

  @Test
  public void testAclGetMemberByIdError() throws IOException {
    thrown.expect(SOAPFaultException.class);
    thrown.expectMessage("Failed to get member by id");

    class MemberServiceMockError extends MemberServiceMock {
      public Member getMemberById(long id) {
        throw getSoapFaultException("Failed to get member by id",
            "uri", "local", "ns0");
      }
    };
    Member member = getMember(1001, "testuser1", "User");
    NodeRights nodeRights = new NodeRights();
    nodeRights.setOwnerRight(getNodeRight(member.getID(), "Owner"));
    NodeMock documentNode = new NodeMock(3000, "DocumentName", "Document");
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    soapFactory.documentManagementMock
        .setNodeRights(documentNode.getID(), nodeRights);
    soapFactory.memberServiceMock = new MemberServiceMockError();
    soapFactory.memberServiceMock.addMember(member);
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doAcl(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/DocumentName:3000")),
        documentNode,
        Proxies.newProxyInstance(Response.class, responseMock));
  }

  @Test
  public void testAclListMembersError() throws IOException {
    thrown.expect(SOAPFaultException.class);
    thrown.expectMessage("Failed to list members");

    class MemberServiceMockError extends MemberServiceMock {
      public Member getMemberById(long id) {
        throw getSoapFaultException("Failed to get member by id",
            "uri", "MemberService.MemberTypeNotValid", "ns0");
      }

      public List<Member> listMembers(long id) {
        throw getSoapFaultException("Failed to list members",
            "uri", "local", "ns0");
      }
    };
    Member member = getMember(1001, "testuser1", "User");
    NodeRights nodeRights = new NodeRights();
    nodeRights.setOwnerRight(getNodeRight(member.getID(), "Owner"));
    NodeMock documentNode = new NodeMock(3000, "DocumentName", "Document");
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    soapFactory.documentManagementMock
        .setNodeRights(documentNode.getID(), nodeRights);
    soapFactory.memberServiceMock = new MemberServiceMockError();
    soapFactory.memberServiceMock.addMember(member);
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doAcl(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/DocumentName:3000")),
        documentNode,
        Proxies.newProxyInstance(Response.class, responseMock));
  }

  @Test
  public void testAclDisabledUser() throws IOException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    User active = (User) getMember(1, "user1", "User");
    User disabled = (User) getMember(2, "user2", "User");
    MemberPrivileges memberPrivileges = disabled.getPrivileges();
    memberPrivileges.setPublicAccessEnabled(true);
    memberPrivileges.setLoginEnabled(false);
    soapFactory.memberServiceMock.addMember(active);
    soapFactory.memberServiceMock.addMember(disabled);
    NodeRights nodeRights = new NodeRights();
    nodeRights.getACLRights().add(getNodeRight(active.getID(), "ACL"));
    nodeRights.getACLRights().add(getNodeRight(disabled.getID(), "ACL"));
    NodeMock documentNode = new NodeMock(3000, "DocumentName", "Document");
    soapFactory.documentManagementMock
        .setNodeRights(documentNode.getID(), nodeRights);

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doAcl(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/DocumentName:3000")),
        documentNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    assertEquals(
        Sets.newHashSet(newUserPrincipal(active.getName())),
        responseMock.getAcl().getPermits());
  }

  @Test
  public void testAclDeletedUser() throws IOException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    User active = (User) getMember(1, "user1", "User");
    User deleted = (User) getMember(2, "user2", "User");
    deleted.setDeleted(true);
    soapFactory.memberServiceMock.addMember(active);
    soapFactory.memberServiceMock.addMember(deleted);
    NodeRights nodeRights = new NodeRights();
    nodeRights.getACLRights().add(getNodeRight(active.getID(), "ACL"));
    nodeRights.getACLRights().add(getNodeRight(deleted.getID(), "ACL"));
    NodeMock documentNode = new NodeMock(3000, "DocumentName", "Document");
    soapFactory.documentManagementMock
        .setNodeRights(documentNode.getID(), nodeRights);

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doAcl(soapFactory.newDocumentManagement("token"),
        new OpentextDocId(new DocId("2000/DocumentName:3000")),
        documentNode,
        Proxies.newProxyInstance(Response.class, responseMock));
    assertEquals(
        Sets.newHashSet(newUserPrincipal(active.getName())),
        responseMock.getAcl().getPermits());
  }

  @Test
  public void testGetGroups() throws InterruptedException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    for (int i = 0; i < 20; i++) {
      soapFactory.memberServiceMock.addMember(
          getMember(1000 + i, "user" + i, "User"));
    }
    for (int i = 0; i < 4; i++) {
      soapFactory.memberServiceMock.addMember(
          getMember(2000 + i, "group" + i, "Group"));
      for (int j = 0; j < 5; j++) {
        soapFactory.memberServiceMock.addMemberToGroup(
            2000 + i,
            soapFactory.memberServiceMock.getMemberById(1000 + (5 * i + j)));
      }
    }
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    Map<GroupPrincipal, List<Principal>> groupDefinitions =
        adaptor.getGroups(soapFactory.newMemberService());
    assertEquals(4, groupDefinitions.size());
    assertEquals(Lists.newArrayList(
            newUserPrincipal("user0"), newUserPrincipal("user1"),
            newUserPrincipal("user2"), newUserPrincipal("user3"),
            newUserPrincipal("user4")),
        groupDefinitions.get(newGroupPrincipal("group0")));
    assertEquals(Lists.newArrayList(
            newUserPrincipal("user5"), newUserPrincipal("user6"),
            newUserPrincipal("user7"), newUserPrincipal("user8"),
            newUserPrincipal("user9")),
        groupDefinitions.get(newGroupPrincipal("group1")));
    assertEquals(Lists.newArrayList(
            newUserPrincipal("user10"), newUserPrincipal("user11"),
            newUserPrincipal("user12"), newUserPrincipal("user13"),
            newUserPrincipal("user14")),
        groupDefinitions.get(newGroupPrincipal("group2")));
    assertEquals(Lists.newArrayList(
            newUserPrincipal("user15"), newUserPrincipal("user16"),
            newUserPrincipal("user17"), newUserPrincipal("user18"),
            newUserPrincipal("user19")),
        groupDefinitions.get(newGroupPrincipal("group3")));
  }

  @Test
  public void testGetGroupsNested() throws InterruptedException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    for (int i = 0; i < 20; i++) {
      soapFactory.memberServiceMock.addMember(
          getMember(1000 + i, "user" + i, "User"));
    }
    for (int i = 0; i < 4; i++) {
      soapFactory.memberServiceMock.addMember(
          getMember(2000 + i, "group" + i, "Group"));
    }
    for (int i = 0; i < 3; i++) {
      soapFactory.memberServiceMock.addMemberToGroup(
          2000, soapFactory.memberServiceMock.getMemberById(1000 + i));
    }
    // Add a group to a group.
    soapFactory.memberServiceMock.addMemberToGroup(
        2001, soapFactory.memberServiceMock.getMemberById(2000));
    soapFactory.memberServiceMock.addMemberToGroup(
        2001, soapFactory.memberServiceMock.getMemberById(1010));
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    Map<GroupPrincipal, List<Principal>> groupDefinitions =
        adaptor.getGroups(soapFactory.newMemberService());
    assertEquals(2, groupDefinitions.size());
    assertEquals(Lists.newArrayList(
            newUserPrincipal("user0"), newUserPrincipal("user1"),
            newUserPrincipal("user2")),
        groupDefinitions.get(newGroupPrincipal("group0")));
    assertEquals(Lists.newArrayList(
            newGroupPrincipal("group0"), newUserPrincipal("user10")),
        groupDefinitions.get(newGroupPrincipal("group1")));
  }

  @Test
  public void testGetGroupsDeletedUser() throws InterruptedException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    Member active = getMember(1, "user1", "User");
    Member deleted = getMember(2, "user2", "User");
    deleted.setDeleted(true);
    Member group = getMember(11, "group1", "Group");
    soapFactory.memberServiceMock.addMember(active);
    soapFactory.memberServiceMock.addMember(deleted);
    soapFactory.memberServiceMock.addMember(group);
    soapFactory.memberServiceMock.addMemberToGroup(group.getID(), active);
    soapFactory.memberServiceMock.addMemberToGroup(group.getID(), deleted);

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    Map<GroupPrincipal, List<Principal>> groupDefinitions =
        adaptor.getGroups(soapFactory.newMemberService());
    assertEquals(Lists.newArrayList(newUserPrincipal("user1")),
        groupDefinitions.get(newGroupPrincipal("group1")));
  }

  @Test
  public void testGetGroupsDeletedGroup() throws InterruptedException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    Member active = getMember(1, "user1", "User");
    Member group = getMember(11, "group1", "Group");
    group.setDeleted(true);
    soapFactory.memberServiceMock.addMember(active);
    soapFactory.memberServiceMock.addMember(group);
    soapFactory.memberServiceMock.addMemberToGroup(group.getID(), active);

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    Map<GroupPrincipal, List<Principal>> groupDefinitions =
        adaptor.getGroups(soapFactory.newMemberService());
    assertEquals(null, groupDefinitions.get(newGroupPrincipal("group1")));
  }

  @Test
  public void testGetGroupsDeletedMemberGroup() throws InterruptedException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    Member active = getMember(1, "user1", "User");
    Member memberGroup = getMember(2, "memberGroup", "Group");
    memberGroup.setDeleted(true);
    Member group = getMember(11, "group1", "Group");
    soapFactory.memberServiceMock.addMember(active);
    soapFactory.memberServiceMock.addMember(memberGroup);
    soapFactory.memberServiceMock.addMember(group);
    soapFactory.memberServiceMock.addMemberToGroup(group.getID(), active);
    soapFactory.memberServiceMock.addMemberToGroup(group.getID(), memberGroup);

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    Map<GroupPrincipal, List<Principal>> groupDefinitions =
        adaptor.getGroups(soapFactory.newMemberService());
    assertEquals(Lists.newArrayList(newUserPrincipal("user1")),
        groupDefinitions.get(newGroupPrincipal("group1")));
  }

  @Test
  public void testGetGroupsLoginDisabledUser() throws InterruptedException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    Member active = getMember(1, "user1", "User");
    User disabled = (User) getMember(2, "user2", "User");
    MemberPrivileges memberPrivileges = disabled.getPrivileges();
    memberPrivileges.setLoginEnabled(false);
    Member group = getMember(11, "group1", "Group");
    soapFactory.memberServiceMock.addMember(active);
    soapFactory.memberServiceMock.addMember(disabled);
    soapFactory.memberServiceMock.addMember(group);
    soapFactory.memberServiceMock.addMemberToGroup(group.getID(), active);
    soapFactory.memberServiceMock.addMemberToGroup(group.getID(), disabled);

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    Map<GroupPrincipal, List<Principal>> groupDefinitions =
        adaptor.getGroups(soapFactory.newMemberService());
    assertEquals(Lists.newArrayList(newUserPrincipal("user1")),
        groupDefinitions.get(newGroupPrincipal("group1")));
  }

  @Test
  public void testGetPublicAccessGroup() throws InterruptedException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    for (int i = 0; i < 20; i++) {
      User user = new User();
      user.setID(1000 + i);
      user.setName("user" + i);
      user.setType("User");
      MemberPrivileges memberPrivileges = new MemberPrivileges();
      memberPrivileges.setLoginEnabled(true);
      memberPrivileges.setPublicAccessEnabled((i % 2) == 0);
      user.setPrivileges(memberPrivileges);
      soapFactory.memberServiceMock.addMember(user);
    }

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    List<Principal> publicAccessGroup =
        adaptor.getPublicAccessGroup(soapFactory.newMemberService());
    assertEquals(Lists.newArrayList(
            newUserPrincipal("user0"), newUserPrincipal("user2"),
            newUserPrincipal("user4"), newUserPrincipal("user6"),
            newUserPrincipal("user8"), newUserPrincipal("user10"),
            newUserPrincipal("user12"), newUserPrincipal("user14"),
            newUserPrincipal("user16"), newUserPrincipal("user18")),
        publicAccessGroup);
  }

  @Test
  public void testGetPublicAccessGroupDisabledUser()
      throws InterruptedException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    User active = (User) getMember(1, "user1", "User");
    MemberPrivileges memberPrivileges = active.getPrivileges();
    memberPrivileges.setPublicAccessEnabled(true);
    User disabled = (User) getMember(2, "user2", "User");
    memberPrivileges = disabled.getPrivileges();
    memberPrivileges.setPublicAccessEnabled(true);
    memberPrivileges.setLoginEnabled(false);
    soapFactory.memberServiceMock.addMember(active);
    soapFactory.memberServiceMock.addMember(disabled);

    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    List<Principal> publicAccessGroup =
        adaptor.getPublicAccessGroup(soapFactory.newMemberService());
    assertEquals(Lists.newArrayList(newUserPrincipal("user1")),
        publicAccessGroup);
  }

  @Test
  public void testGetUserPrincipal() throws InterruptedException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    Member testMember = new Member();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    // unqualified username, no windowsDomain
    testMember.setName("user1");
    assertEquals(new UserPrincipal("user1", LOCAL_NAMESPACE),
        adaptor.getUserPrincipal(testMember));

    // qualified username, no windowsDomain
    testMember.setName("windowsDomain\\user1");
    assertEquals(
        new UserPrincipal("windowsDomain\\user1", GLOBAL_NAMESPACE),
        adaptor.getUserPrincipal(testMember));

    adaptor = new OpentextAdaptor(soapFactory);
    context = ProxyAdaptorContext.getInstance();
    config = initConfig(adaptor, context);
    config.overrideKey("opentext.windowsDomain", "testDomain");
    adaptor.init(context);

    // unqualified username, windowsDomain
    testMember.setName("user1");
    assertEquals(
        new UserPrincipal("testDomain\\user1", GLOBAL_NAMESPACE),
        adaptor.getUserPrincipal(testMember));

    // qualified username, windowsDomain
    testMember.setName("windowsDomain\\user1");
    assertEquals(
        new UserPrincipal("windowsDomain\\user1", GLOBAL_NAMESPACE),
        adaptor.getUserPrincipal(testMember));
  }

  @Test
  public void testGetGroupPrincipal() throws InterruptedException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    Member testMember = new Member();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    // unqualified name
    testMember.setName("group1");
    assertEquals(new GroupPrincipal("group1", LOCAL_NAMESPACE),
        adaptor.getGroupPrincipal(testMember));

    // qualified name
    testMember.setName("windowsDomain\\group1");
    assertEquals(
        new GroupPrincipal("windowsDomain\\group1", GLOBAL_NAMESPACE),
        adaptor.getGroupPrincipal(testMember));
  }

  /**
   * Check that getGroups calls the user/group principal creation
   * helpers appropriately.
   */
  @Test
  public void testGetGroupsWindowsDomain() throws InterruptedException {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    soapFactory.memberServiceMock.addMember(
        getMember(1001, "user1", "User"));
    soapFactory.memberServiceMock.addMember(
        getMember(1002, "user2", "User"));
    soapFactory.memberServiceMock.addMember(
        getMember(1003, "otherDomain\\user3", "User"));
    soapFactory.memberServiceMock.addMember(
        getMember(2001, "group1", "Group"));
    soapFactory.memberServiceMock.addMember(
        getMember(2002, "group2", "Group"));
    soapFactory.memberServiceMock.addMemberToGroup(
        2001, soapFactory.memberServiceMock.getMemberById(1001));
    soapFactory.memberServiceMock.addMemberToGroup(
        2001, soapFactory.memberServiceMock.getMemberById(1002));
    soapFactory.memberServiceMock.addMemberToGroup(
        2001, soapFactory.memberServiceMock.getMemberById(1003));
    soapFactory.memberServiceMock.addMemberToGroup(
        2001, soapFactory.memberServiceMock.getMemberById(2002));
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.windowsDomain", "testDomain");
    adaptor.init(context);
    Map<GroupPrincipal, List<Principal>> groupDefinitions =
        adaptor.getGroups(soapFactory.newMemberService());
    assertEquals(
        Lists.newArrayList(
            new UserPrincipal("testDomain\\user1", GLOBAL_NAMESPACE),
            new UserPrincipal("testDomain\\user2", GLOBAL_NAMESPACE),
            new UserPrincipal("otherDomain\\user3", GLOBAL_NAMESPACE),
            newGroupPrincipal("group2")),
        groupDefinitions.get(newGroupPrincipal("group1")));
  }

  @Test
  public void testDoEmail() throws IOException {
    // Create the definition.
    AttributeGroupDefinition definition = new AttributeGroupDefinition();
    definition.setDisplayName("Email Properties");
    definition.setID(1);
    definition.setKey("1");
    definition.setType("OTEmailProperties");
    PrimitiveAttribute toAttribute = new PrimitiveAttribute();
    toAttribute.setDisplayName("To");
    toAttribute.setID(3);
    toAttribute.setKey("To");
    definition.getAttributes().add(toAttribute);
    PrimitiveAttribute fromAttribute = new PrimitiveAttribute();
    fromAttribute.setDisplayName("From");
    fromAttribute.setID(4);
    fromAttribute.setKey("From");
    definition.getAttributes().add(fromAttribute);
    PrimitiveAttribute subjAttribute = new PrimitiveAttribute();
    subjAttribute.setDisplayName("Subject");
    subjAttribute.setID(2);
    subjAttribute.setKey("Subject");
    definition.getAttributes().add(subjAttribute);
    PrimitiveAttribute emailAttribute = new PrimitiveAttribute();
    emailAttribute.setDisplayName("Email Address");
    emailAttribute.setID(21);
    emailAttribute.setKey("EmailAddress");
    SetAttribute participantsAttributeSet = new SetAttribute();
    participantsAttributeSet.getAttributes().add(emailAttribute);
    definition.getAttributes().add(participantsAttributeSet);

    // Create the test attributes (metadata).
    AttributeGroup attributeGroup = new AttributeGroup();
    attributeGroup.setKey("1");
    attributeGroup.setType("OTEmailProperties");
    StringValue toValue = new StringValue();
    toValue.setDescription("To");
    toValue.setKey("To");
    toValue.getValues().add("to@example.com");
    attributeGroup.getValues().add(toValue);
    StringValue fromValue = new StringValue();
    fromValue.setDescription("From");
    fromValue.setKey("From");
    fromValue.getValues().add("from@example.com");
    attributeGroup.getValues().add(fromValue);
    StringValue subjValue = new StringValue();
    subjValue.setDescription("Subject");
    subjValue.setKey("Subject");
    subjValue.getValues().add("Message Subject");
    attributeGroup.getValues().add(subjValue);
    StringValue emailValue = new StringValue();
    emailValue.setDescription("Email Address");
    emailValue.setKey("EmailAddress");
    emailValue.getValues().add("from@example.com");
    RowValue participantRowValue = new RowValue();
    participantRowValue.getValues().add(emailValue);
    TableValue participantTableValue = new TableValue();
    participantTableValue.getValues().add(participantRowValue);
    attributeGroup.getValues().add(participantTableValue);
    Metadata metadata = new Metadata();
    metadata.getAttributeGroups().add(attributeGroup);

    // Set up the adaptor instance with the test data.
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    DocId docId = new DocId("2000/Email Message:3143");
    OpentextDocId testDocId = new OpentextDocId(docId);
    NodeMock emailNode =
        new NodeMock(3143, "Email Message", "Email");
    emailNode.setMetadata(metadata);
    emailNode.setVersion(1, "application/vnd.ms-outlook",
        new GregorianCalendar(2015, 1, 3, 9, 42, 42));
    soapFactory.documentManagementMock.addNode(emailNode);
    soapFactory.documentManagementMock.addCategoryDefinition(
        definition);

    RequestMock requestMock = new RequestMock(docId);
    Request request = Proxies.newProxyInstance(Request.class, requestMock);
    ResponseMock responseMock = new ResponseMock();
    adaptor.doEmail(soapFactory.newDocumentManagement("token"),
        testDocId, emailNode,
        Proxies.newProxyInstance(Request.class, requestMock),
        Proxies.newProxyInstance(Response.class, responseMock));
    Map<String, List<String>> responseMetadata = responseMock.getMetadata();
    assertEquals(Lists.newArrayList("to@example.com"),
        responseMetadata.get("To"));
    assertEquals(Lists.newArrayList("from@example.com"),
        responseMetadata.get("From"));
    assertEquals(Lists.newArrayList("Message Subject"),
        responseMetadata.get("Subject"));
    assertEquals(Lists.newArrayList("from@example.com"),
        responseMetadata.get("Email Address")); // From Participants table
    // Check that the node version content was also sent.
    assertEquals("application/vnd.ms-outlook", responseMock.contentType);
    assertEquals("this is the content",
        responseMock.outputStream.toString("UTF-8"));
  }

  @Test
  public void testLastModifiedQuery() {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.src", "1234,5678");
    adaptor.init(context);

    String query = adaptor.getLastModifiedQuery();
    assertTrue(query, query.contains("Location_ID1=1234"));
    assertTrue(query, query.contains("Location_ID2=5678"));
    assertTrue(query,
        query.contains("QLREGION+%22OTModifyTime%22%5D+%3E+%22000000%22"));
  }

  @Test
  public void testGetXmlSearchCountResults() throws Exception {
    String response =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<Output>"
        + "  <SearchResultsInformation>"
        + "    <CurrentStartAt>1</CurrentStartAt>"
        + "    <NumberResultsThisPage>23</NumberResultsThisPage>"
        + "  </SearchResultsInformation>"
        + "</Output>";
    assertXmlSearchCount(23, response);
  }

  @Test
  public void testGetXmlSearchCountNoResults() throws Exception {
    assertXmlSearchCount(0, RESPONSE_NO_RESULTS);
  }

  @Test
  public void testGetXmlSearchCountMissing() throws Exception {
    String response =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<Output>"
        + "  <SearchResultsInformation>"
        + "    <CurrentStartAt>1</CurrentStartAt>"
        + "  </SearchResultsInformation>"
        + "</Output>";
    assertXmlSearchCount(0, response);
  }

  @Test
  public void testGetXmlSearchIds() throws Exception {
    String response =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<SearchResult>"
        + "  <OTLocation>"
        + "  <![CDATA[2000 12448 11903 12350 12454 -12454 23336]]>"
        + " </OTLocation>"
        + "</SearchResult>";

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    List<String> ids = adaptor.getXmlSearchIds(parseXml(response));
    assertEquals(
        Lists.newArrayList("2000", "12448", "11903", "12350", "12454", "23336"),
        ids);
  }

  @Test
  public void testGetXmlSearchIdsOneId() throws Exception {
    String response =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<SearchResult>"
        + "  <OTLocation>"
        + "  <![CDATA[2000]]>"
        + " </OTLocation>"
        + "</SearchResult>";

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    List<String> ids = adaptor.getXmlSearchIds(parseXml(response));
    assertEquals(Lists.newArrayList("2000"), ids);
  }

  /* This is just a test of (unexpected) bad output. */
  @Test
  public void testGetXmlSearchIdsMissingIds() throws Exception {
    String response =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<SearchResult>"
        + "</SearchResult>";

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    List<String> ids = adaptor.getXmlSearchIds(parseXml(response));
    assertEquals(Lists.newArrayList(), ids);
  }

  /* This is just a test of (unexpected) bad output. */
  @Test
  public void testGetXmlSearchIdsNonNumericIds() throws Exception {
    String response =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<SearchResult>"
        + "  <OTLocation>"
        + "  <![CDATA[2000 foo 1000]]>"
        + "  </OTLocation>"
        + "</SearchResult>";

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    List<String> ids = adaptor.getXmlSearchIds(parseXml(response));
    assertEquals(Lists.newArrayList(), ids);
    assertNull(response, adaptor.getXmlSearchDocId(parseXml(response)));
  }

  @Test
  public void testGetXmlSearchNames() throws Exception {
    String response =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<SearchResult>"
        + "  <OTLocationPath> "
        + "    <LocationPathString>"
        + "    Enterprise:Folder 1:Folder 2"
        + "    </LocationPathString>"
        + "  </OTLocationPath> "
        + "</SearchResult>";

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);

    List<String> names = adaptor.getXmlSearchNames(parseXml(response));
    assertEquals(
        Lists.newArrayList("Enterprise", "Folder 1", "Folder 2"),
        names);
  }

  @Test
  public void testXmlSearchDocIdStartPointResult() throws Exception {
    String response =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<SearchResult>"
        + "  <OTLocation>"
        + "  <![CDATA[2000 1234 5678]]>"
        + " </OTLocation>"
        + "</SearchResult>";

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.src", "5678");
    adaptor.init(context);

    String docId = adaptor.getXmlSearchDocId(parseXml(response));
    assertEquals("5678:5678", docId);
  }

  @Test
  public void testXmlSearchDocIdNoStartPoint() throws Exception {
    String response =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<SearchResult>"
        + "  <OTLocation>"
        + "  <![CDATA[2000 1234 5678]]>"
        + " </OTLocation>"
        + "</SearchResult>";

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.src", "9012");
    adaptor.init(context);

    String docId = adaptor.getXmlSearchDocId(parseXml(response));
    assertEquals(null, docId);
  }

  @Test
  public void testXmlSearchDocIdNameIdMismatch() throws Exception {
    String response =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<SearchResult>"
        + "  <OTLocation>"
        + "  <![CDATA[2000 1234 5678 9012 3456 7890]]>"
        + " </OTLocation>"
        + "  <OTLocationPath> "
        + "    <LocationPathString>"
        + "    Enterprise:Folder 1"
        + "    </LocationPathString>"
        + "  </OTLocationPath> "
        + "</SearchResult>";

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.src", "1234");
    adaptor.init(context);

    String docId = adaptor.getXmlSearchDocId(parseXml(response));
    assertEquals(null, docId);
  }

  @Test
  public void testXmlSearchDocIdEnterpriseWs() throws Exception {
    String response =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<SearchResult>"
        + "  <OTLocation>"
        + "  <![CDATA[2000]]>"
        + " </OTLocation>"
        + "  <OTLocationPath> "
        + "    <LocationPathString>"
        + "    Enterprise"
        + "    </LocationPathString>"
        + "  </OTLocationPath> "
        + "</SearchResult>";

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.src", "EnterpriseWS");
    adaptor.init(context);

    String docId = adaptor.getXmlSearchDocId(parseXml(response));
    assertEquals("EnterpriseWS:2000", docId);
  }

  @Test
  public void testXmlSearchDocIdNestedStartPoint() throws Exception {
    String response =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<SearchResult>"
        + " <OTLocation>"
        + "  <![CDATA[2000 1234 5678 9012]]>"
        + " </OTLocation>"
        + " <OTLocationPath> "
        + "   <LocationPathString>"
        + "    Enterprise:Folder 1:Folder 2"
        + "   </LocationPathString>"
        + " </OTLocationPath> "
        + " <OTName>"
        + "  Document"
        + "  <Value lang=\"en\">Document</Value>"
        + " </OTName>"
        + "</SearchResult>";

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.src", "1234");
    adaptor.init(context);

    String docId = adaptor.getXmlSearchDocId(parseXml(response));
    assertEquals("1234/Folder+2/Document:9012", docId);
  }

  @Test
  public void testXmlSearchDocIdProject() throws Exception {
    String response =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<SearchResult>"
        + " <OTLocation>"
        + "  <![CDATA[2000 1234 5678 -5678 9012]]>"
        + " </OTLocation>"
        + " <OTLocationPath> "
        + "   <LocationPathString>"
        + "    Enterprise:Folder 1:Project 1"
        + "   </LocationPathString>"
        + " </OTLocationPath> "
        + " <OTName>"
        + "  Document"
        + "  <Value lang=\"en\">Document</Value>"
        + " </OTName>"
        + "</SearchResult>";

    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    config.overrideKey("opentext.src", "1234");
    adaptor.init(context);

    String docId = adaptor.getXmlSearchDocId(parseXml(response));
    assertEquals("1234/Project+1/Document:9012", docId);
  }

  @Test
  public void testGetModifiedDocIds()
      throws IOException, InterruptedException {
    String response1 =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<Output>"
        + "<SearchResults>"
        + "<SearchResult>"
        + "  <OTLocation><![CDATA[2000 1234 5678 -5678 9012]]></OTLocation>"
        + "  <OTLocationPath>"
        + "    <LocationPathString>"
        + "    Enterprise:Folder 1:Project 1"
        + "    </LocationPathString>"
        + "  </OTLocationPath> "
        + "  <OTName>"
        + "    Document in Project"
        + "    <Value lang=\"en\">Document in Project</Value>"
        + "  </OTName>"
        + "</SearchResult>"
        + "<SearchResult>"
        + "  <OTLocation><![CDATA[2000 12340 56780 90120]]></OTLocation>"
        + "  <OTLocationPath>"
        + "    <LocationPathString>"
        + "    Enterprise:Folder 2:Folder 3"
        + "    </LocationPathString>"
        + "  </OTLocationPath> "
        + "  <OTName>"
        + "    Document 2"
        + "    <Value lang=\"en\">Document 2</Value>"
        + "  </OTName>"
        + "</SearchResult>"
        + "<SearchResult>"
        + "  <OTLocation><![CDATA[2000 12341 56781]]></OTLocation>"
        + "  <OTLocationPath>"
        + "    <LocationPathString>"
        + "    Enterprise:Folder 4"
        + "   </LocationPathString>"
        + "  </OTLocationPath> "
        + "  <OTName>"
        + "    Document 3"
        + "    <Value lang=\"en\">Document 3</Value>"
        + "  </OTName>"
        + "</SearchResult>"
        + "</SearchResults>"
        + "<SearchResultsInformation>"
        + "  <CurrentStartAt>1</CurrentStartAt>"
        + "  <NumberResultsThisPage>3</NumberResultsThisPage>"
        + "</SearchResultsInformation>"
        + "</Output>";
    String response2 =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<Output>"
        + "<SearchResults>"
        + "<SearchResult>"
        + "  <OTLocation><![CDATA[2000 1234 5678 -5678 90123]]></OTLocation>"
        + "  <OTLocationPath>"
        + "    <LocationPathString>"
        + "    Enterprise:Folder 1:Project 1"
        + "    </LocationPathString>"
        + "  </OTLocationPath> "
        + "  <OTName>"
        + "    URL in Project"
        + "    <Value lang=\"en\">URL in Project</Value>"
        + "  </OTName>"
        + "</SearchResult>"
        + "</SearchResults>"
        + "<SearchResultsInformation>"
        + "  <CurrentStartAt>1</CurrentStartAt>"
        + "  <NumberResultsThisPage>1</NumberResultsThisPage>"
        + "</SearchResultsInformation>"
        + "</Output>";

    HttpServer server = startServer(response1, response2, RESPONSE_NO_RESULTS);
    try {
      SoapFactoryMock soapFactory = new SoapFactoryMock();
      OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
      AdaptorContext context = ProxyAdaptorContext.getInstance();
      Config config = initConfig(adaptor, context);
      config.overrideKey("opentext.displayUrl.contentServerUrl",
          "http://127.0.0.1:" + server.getAddress().getPort() + "/");
      config.overrideKey("opentext.src", "1234, 56780, 12341");
      adaptor.init(context);

      DocIdPusherMock docIdPusherMock = new DocIdPusherMock();
      adaptor.getModifiedDocIds(
          Proxies.newProxyInstance(DocIdPusher.class, docIdPusherMock));
      assertEquals(Lists.newArrayList(
              new DocId("1234/Project+1/Document+in+Project:9012"),
              new DocId("56780/Document+2:90120"),
              new DocId("12341/Document+3:56781"),
              new DocId("1234/Project+1/URL+in+Project:90123")),
          docIdPusherMock.docIds);
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void testGetModifiedDocIdsNoResults()
      throws IOException, InterruptedException {
    HttpServer server = startServer(RESPONSE_NO_RESULTS);
    try {
      SoapFactoryMock soapFactory = new SoapFactoryMock();
      OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
      AdaptorContext context = ProxyAdaptorContext.getInstance();
      Config config = initConfig(adaptor, context);
      config.overrideKey("opentext.displayUrl.contentServerUrl",
          "http://127.0.0.1:" + server.getAddress().getPort() + "/");
      config.overrideKey("opentext.src", "1234, 56780, 12341");
      adaptor.init(context);

      DocIdPusherMock docIdPusherMock = new DocIdPusherMock();
      adaptor.getModifiedDocIds(
          Proxies.newProxyInstance(DocIdPusher.class, docIdPusherMock));
      assertEquals(Lists.newArrayList(), docIdPusherMock.docIds);
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void testGetModifiedDocIdsCheckpoint()
      throws IOException, InterruptedException {
    String response =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<Output>"
        + "<SearchResults>"
        + "<SearchResult>"
        + "  <OTLocation><![CDATA[2000 1234 5678 -5678 9012]]></OTLocation>"
        + "  <OTLocationPath>"
        + "    <LocationPathString>"
        + "    Enterprise:Folder 1:Project 1"
        + "    </LocationPathString>"
        + "  </OTLocationPath> "
        + "  <OTName>"
        + "    Document in Project"
        + "    <Value lang=\"en\">Document in Project</Value>"
        + "  </OTName>"
        + "</SearchResult>"
        + "</SearchResults>"
        + "<SearchResultsInformation>"
        + "  <CurrentStartAt>1</CurrentStartAt>"
        + "  <NumberResultsThisPage>1</NumberResultsThisPage>"
        + "</SearchResultsInformation>"
        + "</Output>";

    HttpServer server = startServer(response, RESPONSE_NO_RESULTS);
    try {
      SoapFactoryMock soapFactory = new SoapFactoryMock();
      OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
      AdaptorContext context = ProxyAdaptorContext.getInstance();
      Config config = initConfig(adaptor, context);
      config.overrideKey("opentext.displayUrl.contentServerUrl",
          "http://127.0.0.1:" + server.getAddress().getPort() + "/");
      config.overrideKey("opentext.src", "1234");
      adaptor.init(context);

      // Create the node; note that the test server doesn't
      // actually search by date, so we can use a fixed date
      // here and still have it returned.
      NodeMock node = new NodeMock(9012, "Document in Project", "Document");
      node.setModifyDate(2013, 3, 1, 4, 34, 21);
      soapFactory.documentManagementMock.addNode(node);
      DocIdPusherMock docIdPusherMock = new DocIdPusherMock();
      adaptor.getModifiedDocIds(
          Proxies.newProxyInstance(DocIdPusher.class, docIdPusherMock));
      assertEquals(Lists.newArrayList(
              new DocId("1234/Project+1/Document+in+Project:9012")),
          docIdPusherMock.docIds);

      // Check that the adaptor stored the last modified
      // date/time for the last item in the search results and
      // uses the stored data the next time the search query is
      // generated.
      String query = adaptor.getLastModifiedQuery();
      assertTrue(query,
          query.contains("QLREGION+%22OTModifyTime%22%5D+%3E+%22043421%22"));
      assertTrue(query,
          query.contains("QLREGION+%22OTModifyDate%22%5D+%3D+%2220130401%22"));
    } finally {
      server.stop(0);
    }
  }

  private void assertXmlSearchCount(int expectedCount, String response)
      throws Exception {
    SoapFactoryMock soapFactory = new SoapFactoryMock();
    OpentextAdaptor adaptor = new OpentextAdaptor(soapFactory);
    AdaptorContext context = ProxyAdaptorContext.getInstance();
    Config config = initConfig(adaptor, context);
    adaptor.init(context);
    assertEquals(expectedCount,
        adaptor.getXmlSearchCount(parseXml(response)));
  }

  private Element parseXml(String source) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    return builder.parse(
        new InputSource(new StringReader(source))).getDocumentElement();
  }

  private HttpServer startServer(final String... response) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/").setHandler(
        new HttpHandler() {
          int responseIndex = 0;
          public void handle(HttpExchange exchange) throws IOException {
            byte[] responseBytes;
            if (responseIndex < response.length) {
              responseBytes = response[responseIndex].getBytes(UTF_8);
              responseIndex++;
            } else {
              responseBytes = new byte[0];
            }
            exchange.sendResponseHeaders(200, responseBytes.length);
            OutputStream out = exchange.getResponseBody();
            IOHelper.copyStream(new ByteArrayInputStream(responseBytes), out);
            exchange.close();
          }
        });
    server.start();
    return server;
  }


  private class SoapFactoryMock implements SoapFactory {
    private DsAuthenticationMock dsAuthenticationMock;
    private AuthenticationMock authenticationMock;
    private DocumentManagementMock documentManagementMock;
    private ContentServiceMock contentServiceMock;
    private MemberServiceMock memberServiceMock;
    private CollaborationMock collaborationMock;
    private boolean hasDsUrl;

    private SoapFactoryMock() {
      this.dsAuthenticationMock = new DsAuthenticationMock();
      this.authenticationMock = new AuthenticationMock();
      this.documentManagementMock = new DocumentManagementMock();
      this.contentServiceMock = new ContentServiceMock();
      this.memberServiceMock = new MemberServiceMock();
      this.collaborationMock = new CollaborationMock();
    }

    @Override
    public com.opentext.ecm.services.authws.Authentication
        newDsAuthentication() {
      if (!hasDsUrl) {
        return null;
      }
      return Proxies.newProxyInstance(
          com.opentext.ecm.services.authws.Authentication.class,
          this.dsAuthenticationMock);
    }

    @Override
    public Authentication newAuthentication() {
      return Proxies.newProxyInstance(Authentication.class,
          this.authenticationMock);
    }

    @Override
    public DocumentManagement newDocumentManagement(
        String authenticationToken) {
      return Proxies.newProxyInstance(DocumentManagement.class,
          this.documentManagementMock);
    }

    private DocumentManagement newDocumentManagement(
        DocumentManagementMock documentManagementMock) {
      return Proxies.newProxyInstance(DocumentManagement.class,
          documentManagementMock);
    }

    @Override
    public ContentService newContentService(
        DocumentManagement documentManagement) {
      return Proxies.newProxyInstance(ContentService.class,
          this.contentServiceMock);
    }

    @Override
    public MemberService newMemberService(
        DocumentManagement documentManagement) {
      return Proxies.newProxyInstance(MemberService.class,
          this.memberServiceMock);
    }

    private MemberService newMemberService() {
      return Proxies.newProxyInstance(MemberService.class,
          this.memberServiceMock);
    }

    @Override
    public Collaboration newCollaboration(
        DocumentManagement documentManagement) {
      return Proxies.newProxyInstance(Collaboration.class,
          this.collaborationMock);
    }

    @Override
    public void configure(Config config) {
      this.hasDsUrl =
          !config.getValue("opentext.directoryServicesUrl").isEmpty();
    }

    @Override
    public void setServer(OpentextAdaptor.CwsServer server) {
    }
  }

  private class DsAuthenticationMock {
    private boolean authenticateCalled;
    private String faultCode;
    private String message;

    public String authenticate(String user, String password)
        throws AuthenticationException_Exception {
      this.authenticateCalled = true;
      if (this.faultCode != null) {
        AuthenticationException e = new AuthenticationException();
        e.setFaultCode(this.faultCode);
        e.setMessage(this.message);
        throw new AuthenticationException_Exception(e.getMessage(), e);
      }
      return "dsToken";
    }
  }

  private class AuthenticationMock {
    private boolean authenticateUserCalled;
    private boolean validateUserCalled;
    private String authenticationToken;

    public String authenticateUser(String username, String password)
        throws SOAPFaultException {
      this.authenticateUserCalled = true;

      if ("validuser".equals(username) && "validpassword".equals(password)) {
        this.authenticationToken = "authentication_token";
        return this.authenticationToken;
      }
      if ("invaliduser".equals(username) && "validpassword".equals(password)) {
        throw getSoapFaultException("javax.xml.ws.soap.SOAPFaultException",
            "urn:Core.service.livelink.opentext.com",
            "Core.LoginFailed", "ns0");
      }
      if ("validuser".equals(username) && "invalidpassword".equals(password)) {
        throw getSoapFaultException("Invalid username/password specified.",
            "urn:Core.service.livelink.opentext.com",
            "Core.LoginFailed", "ns0");
      }
      if ("other".equals(password)) {
        throw getSoapFaultException("Other SOAPFaultException",
            "urn:opentextadaptortest", "Test.OtherException", "ns0");
      }
      throw new AssertionError(
          "Unexpected test config: " + username + "/" + password);
    }

    public String validateUser(String token) throws SOAPFaultException {
      this.validateUserCalled = true;
      this.authenticationToken = "validation_token";
      return this.authenticationToken;
    }
  }

  private SOAPFaultException getSoapFaultException(String message,
      String uri, String localPart, String prefix) {
    try {
      SOAPFactory soapFactory = SOAPFactory.newInstance();
      SOAPFault soapFault = soapFactory.createFault(
          message, new QName(uri, localPart, prefix));
      return new SOAPFaultException(soapFault);
    } catch (SOAPException soapException) {
      throw new RuntimeException("Failed to create SOAPFaultException",
          soapException);
    }
  }

  private class DocIdPusherMock {
    private List<DocId> docIds;
    private Map<GroupPrincipal, List<Principal>> groupDefinitions;

    public DocId pushDocIds(Iterable<DocId> docIds) {
      this.docIds = Lists.newArrayList(docIds);
      return null;
    }

    public void pushGroupDefinitions(
        Map<GroupPrincipal, List<Principal>> groupDefinitions,
        boolean caseSensitive) {
      this.groupDefinitions = groupDefinitions;
    }
  }

  private class DocumentManagementMock {
    List<NodeMock> nodes = new ArrayList<NodeMock>();
    List<AttributeGroupDefinition> categoryDefinitions;
    Map<Long, NodeRights> nodeRights = new HashMap<Long, NodeRights>();

    DocumentManagementMock() {
      this.nodes.add(new NodeMock(2000, "Enterprise Workspace"));
      this.nodes.add(new NodeMock(1001, "test node 1001"));
      this.nodes.add(new NodeMock(1003, "test node 1003"));
    }

    private void addNode(NodeMock node) {
      nodes.add(node);
    }

    private NodeMock findNode(long nodeId) {
      for (NodeMock node : this.nodes) {
        if (node.getID() == nodeId) {
          return node;
        }
      }
      return null;
    }

    private void addCategoryDefinition(
        AttributeGroupDefinition categoryDefinition) {
      if (this.categoryDefinitions == null) {
        this.categoryDefinitions =
            new ArrayList<AttributeGroupDefinition>();
      }
      this.categoryDefinitions.add(categoryDefinition);
    }

    private void setNodeRights(long id, NodeRights nodeRights) {
      this.nodeRights.put(id, nodeRights);
    }

    public Node getNode(long nodeId) {
      if (nodeId == 1002) { // Invalid ID for testing.
        return null;
      }
      return findNode(nodeId);
    }

    public Node getRootNode(String rootNodeType) {
      if ("EnterpriseWS".equals(rootNodeType)) {
        return findNode(2000);
      }
      return null;
    }

    public Node getNodeByPath(long containerNodeId, List<String> path) {
      for (NodeMock node : this.nodes) {
        if (node.getStartPointId() == containerNodeId
            && node.getPath().equals(path)) {
          return node;
        }
      }
      return null;
    }

    // TODO: use parent id instead.
    public List<Node> listNodes(long containerNodeId, boolean partialData) {
      List<Node> results = new ArrayList<Node>();
      NodeMock containerNode = findNode(containerNodeId);
      List<String> containerPath = containerNode.getPath();
      for (NodeMock node : this.nodes) {
        List<String> nodePath = node.getPath();
        if (nodePath == null) {
          continue;
        }
        if ((containerPath.size() + 1) == nodePath.size()
            && containerPath.equals(nodePath.subList(0, nodePath.size() - 1))) {
          results.add(node);
        }
      }
      return results;
    }

    public Version getVersion(long nodeId, long versionNumber) {
      NodeMock node = findNode(nodeId);
      return node.getVersion();
    }

    public String getVersionContentsContext(long nodeId, long versionNumber) {
      return "versioncontext";
    }

    public AttributeGroupDefinition getAttributeGroupDefinition(
        String type, String key) {
      for (AttributeGroupDefinition categoryDef : this.categoryDefinitions) {
        if (key.equals(categoryDef.getKey())) {
          return categoryDef;
        }
      }
      return null;
    }

    public NodeRights getNodeRights(long id) {
      NodeRights nodeRights = this.nodeRights.get(id);
      if (nodeRights != null) {
        return nodeRights;
      }
      throw getSoapFaultException("no node rights", "uri", "local", "prefix");
    }
  }

  private class ContentServiceMock {
    private static final String DEFAULT_CONTENT = "this is the content";

    public DataHandler downloadContent(String contextId) {
      DataSource dataSource = new DataSource() {
          public String getContentType() {
            return "text/plain";
          }

          public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(DEFAULT_CONTENT.getBytes(UTF_8));
          }

          public String getName() {
            return "DataSourceName";
          }

          public OutputStream getOutputStream() throws IOException {
            throw new IOException("Not available");
          }
        };

      return new DataHandler(dataSource);
    }
  }

  private class MemberServiceMock {
    private List<Member> members = new ArrayList<Member>();
    private Map<Long, List<Member>> groupMembers =
        new HashMap<Long, List<Member>>();

    private void addMember(Member member) {
      this.members.add(member);
    }

    private void addMemberToGroup(long group, Member member) {
      List<Member> members = this.groupMembers.get(group);
      if (members == null) {
        members = new ArrayList<Member>();
      }
      members.add(member);
      this.groupMembers.put(group, members);
    }

    public Member getMemberById(long id) {
      for (Member member : this.members) {
        if (member.getID() == id) {
          if (!("User".equals(member.getType())
                  || "Group".equals(member.getType()))) {
            // This method is documented to throw this exception
            // if the returned member type is not a group or a
            // user.
            throw getSoapFaultException(
                "MemberService.MemberTypeNotValid", "uri",
                "MemberService.MemberTypeNotValid", "prefix");
          }
          return member;
        }
      }
      return null;
    }

    public List<Member> getMembersByID(List<Long> idList) {
      List<Member> memberList = new ArrayList<Member>();
      for (Long id : idList) {
        for (Member member : this.members) {
          if (member.getID() == id) {
            memberList.add(member);
          } else {
            memberList.add(null);
          }
        }
      }
      return memberList;
    }

    public List<Member> listMembers(long id) {
      List<Member> members = this.groupMembers.get(id);
      if (members == null) {
        members = new ArrayList<Member>();
      }
      return members;
    }

    // Only supports searching by type.
    public PageHandle searchForMembers(MemberSearchOptions searchOptions) {
      List<Member> results = new ArrayList<Member>();
      for (Member member : this.members) {
        if (SearchFilter.GROUP.equals(searchOptions.getFilter())
            && "Group".equals(member.getType())) {
          results.add(member);
        } else if (SearchFilter.USER.equals(searchOptions.getFilter())
            && "User".equals(member.getType())) {
          results.add(member);
        }
      }
      if (results.size() == 0) {
        return null;
      }
      return new PageHandleMock(results, searchOptions.getPageSize());
    }

    public MemberSearchResults getSearchResults(PageHandle pageHandle) {
      return new MemberSearchResultsMock((PageHandleMock) pageHandle);
    }
  }

  private class MemberSearchResultsMock extends MemberSearchResults {
    List<Member> results;

    private MemberSearchResultsMock(PageHandleMock pageHandle) {
      super.setPageHandle(pageHandle);
      this.results = pageHandle.getNextResults();
    }

    @Override
    public List<Member> getMembers() {
      return this.results;
    }
  }

  private class PageHandleMock extends PageHandle {
    private List<Member> results;
    private int pageSize = -1;
    private int index = 0;

    private PageHandleMock(List<Member> results, int pageSize) {
      this.results = results;
      this.pageSize = pageSize;
    }

    List<Member> getNextResults() {
      if (this.index >= this.results.size()) {
        return null;
      }
      List<Member> nextResults = this.results.subList(this.index,
          Math.min(this.index + this.pageSize, this.results.size()));
      this.index += this.pageSize;
      return nextResults;
    }

    /* As best I can tell, isFinalPage should be true when the
     * final page has been returned, not when the next call to
     * get results will return the final page.
     */
    @Override
    public boolean isFinalPage() {
      return this.index >= this.results.size();
    }
  }

  private class CollaborationMock {
    private List<MilestoneInfo> milestoneInfo =
        new ArrayList<MilestoneInfo>();
    private List<NewsInfo> newsInfo =
        new ArrayList<NewsInfo>();
    private List<ProjectInfo> projectInfo =
        new ArrayList<ProjectInfo>();
    private List<DiscussionItem> discussionItems =
        new ArrayList<DiscussionItem>();
    private List<TaskInfo> taskInfo =
        new ArrayList<TaskInfo>();

    public MilestoneInfo getMilestone(long id) {
      for (MilestoneInfo info : this.milestoneInfo) {
        if (info.getID() == id) {
          return info;
        }
      }
      throw getSoapFaultException("Collaboration.ObjectIDInvalid", "uri",
          "Collaboration.ObjectIDInvalid", "prefix");
    }

    public NewsInfo getNews(long id) {
      for (NewsInfo info : this.newsInfo) {
        if (info.getID() == id) {
          return info;
        }
      }
      throw getSoapFaultException("Collaboration.ObjectIDInvalid", "uri",
          "Collaboration.ObjectIDInvalid", "prefix");
    }

    public ProjectInfo getProject(long id) {
      for (ProjectInfo info : this.projectInfo) {
        if (info.getID() == id) {
          return info;
        }
      }
      throw getSoapFaultException("Collaboration.ObjectIDInvalid", "uri",
          "Collaboration.ObjectIDInvalid", "prefix");
    }

    public DiscussionItem getTopicReply(long id) {
      for (DiscussionItem item : this.discussionItems) {
        if (item.getID() == id) {
          return item;
        }
      }
      throw getSoapFaultException("Collaboration.ObjectIDInvalid", "uri",
          "Collaboration.ObjectIDInvalid", "prefix");
    }

    public TaskInfo getTask(long id) {
      for (TaskInfo info : this.taskInfo) {
        if (info.getID() == id) {
          return info;
        }
      }
      throw getSoapFaultException("Collaboration.ObjectIDInvalid", "uri",
          "Collaboration.ObjectIDInvalid", "prefix");
    }

    private void addMilestoneInfo(MilestoneInfo milestoneInfo) {
      this.milestoneInfo.add(milestoneInfo);
    }

    private void addNewsInfo(NewsInfo newsInfo) {
      this.newsInfo.add(newsInfo);
    }

    private void addProjectInfo(ProjectInfo projectInfo) {
      this.projectInfo.add(projectInfo);
    }

    private void addDiscussionItem(DiscussionItem discussionItem) {
      this.discussionItems.add(discussionItem);
    }

    private void addTaskInfo(TaskInfo taskInfo) {
      this.taskInfo.add(taskInfo);
    }
  }

  private class NodeMock extends Node {
    private long id;
    private String name;
    private boolean isVersionable;
    private String objectType;
    private long parentId;
    private Metadata metadata;
    private XMLGregorianCalendar modifyDate;
    private XMLGregorianCalendar createDate;
    private NodeVersionInfo nodeVersionInfo;

    private List<String> path;
    private long startPointId;
    private Version version;

    private NodeMock(long id, String name) {
      this.id = id;
      this.name = name;
      this.isVersionable = true;
    }

    private NodeMock(long id, String name, String objectType) {
      this(id, name);
      this.objectType = objectType;
    }

    @Override
    public long getID() {
      return this.id;
    }

    @Override
    public String getName() {
      return this.name;
    }

    @Override
    public boolean isIsVersionable() {
      return isVersionable;
    }

    @Override
    public void setIsVersionable(boolean isVersionable) {
      this.isVersionable = isVersionable;
    }

    @Override
    public String getType() {
      return this.objectType;
    }

    @Override
    public void setType(String objectType) {
      this.objectType = objectType;
    }

    @Override
    public void setParentID(long parentId) {
      this.parentId = parentId;
    }

    @Override
    public long getParentID() {
      return this.parentId;
    }

    @Override
    public Metadata getMetadata() {
      return this.metadata;
    }

    @Override
    public void setMetadata(Metadata metadata) {
      this.metadata = metadata;
    }

    @Override
    public XMLGregorianCalendar getModifyDate() {
      return this.modifyDate;
    }

    @Override
    public XMLGregorianCalendar getCreateDate() {
      return this.createDate;
    }

    @Override
    public NodeVersionInfo getVersionInfo() {
      return this.nodeVersionInfo;
    }

    @Override
    public void setVersionInfo(NodeVersionInfo nodeVersionInfo) {
      this.nodeVersionInfo = nodeVersionInfo;
    }

    // For testing getNodeByPath
    long getStartPointId() {
      return this.startPointId;
    }

    void setStartPointId(long startPointId) {
      this.startPointId = startPointId;
    }

    // For testing getNodeByPath
    List<String> getPath() {
      return this.path;
    }

    void setPath(String... path) {
      this.path = Arrays.asList(path);
    }

    Version getVersion() {
      return this.version;
    }

    void setVersion(long versionNumber, String contentType,
        GregorianCalendar modifyDate) {
      this.version
          = new VersionMock(versionNumber, contentType, modifyDate);
    }

    private void setModifyDate(int year, int month, int dayOfMonth,
        int hourOfDay, int minute, int second) {
      this.modifyDate = getXmlGregorianCalendar(
          year, month, dayOfMonth, hourOfDay, minute, second);
    }

    private void setCreateDate(int year, int month, int dayOfMonth,
        int hourOfDay, int minute, int second) {
      this.createDate = getXmlGregorianCalendar(
          year, month, dayOfMonth, hourOfDay, minute, second);
    }

    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append(name)
          .append("(").append(id).append(")")
          .append("[").append(startPointId).append("/");
      Joiner.on("/").appendTo(builder, path);
      builder.append("]");
      return builder.toString();
    }
  }

  private class VersionMock extends Version {
    private long versionNumber;
    private String contentType;
    private GregorianCalendar modifyDate;
    private long fileDataSize =
        ContentServiceMock.DEFAULT_CONTENT.getBytes(UTF_8).length;

    private VersionMock(long versionNumber, String contentType,
        GregorianCalendar modifyDate) {
      this.versionNumber = versionNumber;
      this.contentType = contentType;
      this.modifyDate = modifyDate;
    }

    @Override
    public long getNumber() {
      return this.versionNumber;
    }

    @Override
    public String getMimeType() {
      return this.contentType;
    }

    @Override
    public XMLGregorianCalendar getModifyDate() {
      try {
        return
            DatatypeFactory.newInstance().newXMLGregorianCalendar(modifyDate);
      } catch (DatatypeConfigurationException datatypeException) {
        return null;
      }
    }

    @Override
    public long getFileDataSize() {
      return this.fileDataSize;
    }

    @Override
    public void setFileDataSize(long size) {
      this.fileDataSize = size;
    }
  }

  private class RequestMock {
    private DocId docId;
    private Date lastAccessTime;

    RequestMock(DocId docId) {
      this.docId = docId;
    }

    RequestMock(DocId docId, Date lastAccessTime) {
      this.docId = docId;
      this.lastAccessTime = lastAccessTime;
    }

    public boolean canRespondWithNoContent(Date lastModified) {
      return !hasChangedSinceLastAccess(lastModified);
    }

    public DocId getDocId() {
      return this.docId;
    }

    public Date getLastAccessTime() {
      return this.lastAccessTime;
    }

    public boolean hasChangedSinceLastAccess(Date lastModified) {
      return (lastAccessTime == null || lastModified == null) ? true
          : lastModified.after(lastAccessTime);
    }
  }

  private class ResponseMock {
    private ByteArrayOutputStream outputStream;
    private String contentType;
    private Date lastModified;
    private URI displayUrl;
    private boolean noContent = false;
    private boolean notFound = false;
    private Map<String, List<String>> metadata =
        new HashMap<String, List<String>>();
    private List<String> anchors = new ArrayList<String>();
    private Acl acl;
    private boolean noIndex;

    ResponseMock() {
      this.outputStream = new ByteArrayOutputStream();
    }

    public OutputStream getOutputStream() {
      return outputStream;
    }

    public void setContentType(String contentType) {
      this.contentType = contentType;
    }

    public void setLastModified(Date lastModified) {
      this.lastModified = lastModified;
    }

    public void setDisplayUrl(URI displayUrl) {
      this.displayUrl = displayUrl;
    }

    public void respondNoContent() {
      this.noContent = true;
    }

    public void respondNotFound() {
      this.notFound = true;
    }

    public void addMetadata(String name, String value) {
      List<String> values = this.metadata.get(name);
      if (values == null) {
        values = new ArrayList<String>();
        this.metadata.put(name, values);
      }
      values.add(value);
    }

    public void addAnchor(URI uri, String text) {
      this.anchors.add(text);
    }

    public void setAcl(Acl acl) {
      this.acl = acl;
    }

    public void setNoIndex(boolean noIndex) {
      this.noIndex = noIndex;
    }

    private boolean notFound() {
      return this.notFound;
    }

    private Map<String, List<String>> getMetadata() {
      return this.metadata;
    }

    private List<String> getAnchors() {
      return this.anchors;
    }

    private Acl getAcl() {
      return this.acl;
    }
  }

  void assertStartPointEquals(StartPoint actual,
      StartPoint.Type expectedType, String expectedName, int expectedNodeId) {
    assertEquals(expectedType, actual.getType());
    assertEquals(expectedName, actual.getName());
    assertEquals(expectedNodeId, actual.getNodeId());
  }

  // Set all the required config properties.
  private Config initConfig(OpentextAdaptor adaptor, AdaptorContext context) {
    Config config = context.getConfig();
    adaptor.initConfig(config);
    config.overrideKey("opentext.username", "validuser");
    config.overrideKey("opentext.password", "validpassword");
    config.overrideKey("opentext.webServicesUrl",
        "http://example.com/les-services/services");
    config.overrideKey("opentext.displayUrl.contentServerUrl",
        "http://example.com/otcs/livelink.exe");
    config.overrideKey("adaptor.namespace", GLOBAL_NAMESPACE);
    return config;
  }

  private XMLGregorianCalendar getXmlGregorianCalendar(
      int year, int month, int dayOfMonth,
      int hourOfDay, int minute, int second) {
    GregorianCalendar calendar = new GregorianCalendar(
        year, month, dayOfMonth, hourOfDay, minute, second);
    try {
      return DatatypeFactory.newInstance().newXMLGregorianCalendar(calendar);
    } catch (DatatypeConfigurationException datatypeException) {
      return null;
    }
  }

  private NodeRight getNodeRight(long rightId, String type) {
    NodePermissions nodePermissions = new NodePermissions();
    nodePermissions.setSeeContentsPermission(true);
    NodeRight nodeRight = new NodeRight();
    nodeRight.setRightID(rightId);
    nodeRight.setType(type);
    nodeRight.setPermissions(nodePermissions);
    return nodeRight;
  }

  private Member getMember(long id, String name, String type) {
    Member member;
    if (type.equals("User")) {
      MemberPrivileges priv = new MemberPrivileges();
      priv.setLoginEnabled(true);
      member = new User();
      ((User) member).setPrivileges(priv);
    } else {
      member = new Group();
    }
    member.setID(id);
    member.setName(name);
    member.setType(type);
    return member;
  }

  private UserPrincipal newUserPrincipal(String name) {
    return new UserPrincipal(name, LOCAL_NAMESPACE);
  }

  private GroupPrincipal newGroupPrincipal(String name) {
    return new GroupPrincipal(name, LOCAL_NAMESPACE);
  }
}
