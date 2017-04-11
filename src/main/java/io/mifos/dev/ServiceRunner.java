/*
 * Copyright 2017 The Mifos Initiative.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mifos.dev;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import io.mifos.accounting.api.v1.client.LedgerManager;
import io.mifos.anubis.api.v1.domain.AllowedOperation;
import io.mifos.anubis.api.v1.domain.Signature;
import io.mifos.core.api.config.EnableApiFactory;
import io.mifos.core.api.context.AutoSeshat;
import io.mifos.core.api.context.AutoUserContext;
import io.mifos.core.api.util.ApiConstants;
import io.mifos.core.api.util.ApiFactory;
import io.mifos.core.lang.TenantContextHolder;
import io.mifos.core.lang.security.RsaPublicKeyBuilder;
import io.mifos.core.test.env.TestEnvironment;
import io.mifos.core.test.listener.EventRecorder;
import io.mifos.core.test.servicestarter.ActiveMQForTest;
import io.mifos.core.test.servicestarter.EurekaForTest;
import io.mifos.core.test.servicestarter.IntegrationTestEnvironment;
import io.mifos.core.test.servicestarter.Microservice;
import io.mifos.customer.api.v1.client.CustomerManager;
import io.mifos.identity.api.v1.EventConstants;
import io.mifos.identity.api.v1.client.IdentityManager;
import io.mifos.identity.api.v1.domain.Authentication;
import io.mifos.identity.api.v1.domain.Password;
import io.mifos.identity.api.v1.domain.Permission;
import io.mifos.identity.api.v1.domain.Role;
import io.mifos.identity.api.v1.domain.UserWithPassword;
import io.mifos.office.api.v1.client.OrganizationManager;
import io.mifos.portfolio.api.v1.client.PortfolioManager;
import io.mifos.provisioner.api.v1.client.Provisioner;
import io.mifos.provisioner.api.v1.domain.Application;
import io.mifos.provisioner.api.v1.domain.AssignedApplication;
import io.mifos.provisioner.api.v1.domain.AuthenticationResponse;
import io.mifos.provisioner.api.v1.domain.CassandraConnectionInfo;
import io.mifos.provisioner.api.v1.domain.DatabaseConnectionInfo;
import io.mifos.provisioner.api.v1.domain.IdentityManagerInitialization;
import io.mifos.provisioner.api.v1.domain.Tenant;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.Base64Utils;

import java.security.PublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("SpringAutowiredFieldsWarningInspection")
@RunWith(SpringRunner.class)
@SpringBootTest()
public class ServiceRunner {
  private static final String CLIENT_ID = "service-runner";
  private static Microservice<Provisioner> provisionerService;
  private static Microservice<IdentityManager> identityService;
  private static Microservice<OrganizationManager> officeClient;
  private static Microservice<CustomerManager> customerClient;
  private static Microservice<LedgerManager> accountingClient;
  private static Microservice<PortfolioManager> portfolioClient;

  private static DB embeddedMariaDb;

  @Configuration
  @ActiveMQForTest.EnableActiveMQListen
  @EnableApiFactory
  @ComponentScan("io.mifos.dev.listener")
  public static class TestConfiguration {
    public TestConfiguration() {
      super();
    }

    @Bean()
    public Logger logger() {
      return LoggerFactory.getLogger("test-logger");
    }
  }

  @ClassRule
  public static final EurekaForTest EUREKA_FOR_TEST = new EurekaForTest();

  @ClassRule
  public static final ActiveMQForTest ACTIVE_MQ_FOR_TEST = new ActiveMQForTest();

  @ClassRule
  public static final IntegrationTestEnvironment INTEGRATION_TEST_ENVIRONMENT = new IntegrationTestEnvironment("fineract-demo");

  @Autowired
  private ApiFactory apiFactory;

  @Autowired
  private EventRecorder eventRecorder;

  public ServiceRunner() {
    super();
  }

  @Before
  public void before() throws Exception
  {
    // start embedded Cassandra
    EmbeddedCassandraServerHelper.startEmbeddedCassandra(TimeUnit.SECONDS.toMillis(30L));
    // start embedded MariaDB
    ServiceRunner.embeddedMariaDb = DB.newEmbeddedDB(
        DBConfigurationBuilder.newBuilder()
            .setPort(3306)
            .build()
    );
    ServiceRunner.embeddedMariaDb.start();

    ServiceRunner.provisionerService =
        new Microservice<>(Provisioner.class, "provisioner", "0.1.0-BUILD-SNAPSHOT", ServiceRunner.INTEGRATION_TEST_ENVIRONMENT);
    final TestEnvironment provisionerTestEnvironment = provisionerService.getProcessEnvironment();
    provisionerTestEnvironment.addSystemPrivateKeyToProperties();
    provisionerTestEnvironment.setProperty("system.initialclientid", ServiceRunner.CLIENT_ID);
    ServiceRunner.provisionerService.start();
    ServiceRunner.provisionerService.setApiFactory(apiFactory);

    ServiceRunner.identityService = this.startService(IdentityManager.class, "identity");
    ServiceRunner.officeClient = this.startService(OrganizationManager.class, "office");
    ServiceRunner.customerClient = this.startService(CustomerManager.class, "customer");
    ServiceRunner.accountingClient = this.startService(LedgerManager.class, "accounting");
    ServiceRunner.portfolioClient = this.startService(PortfolioManager.class, "portfolio");
  }

  @After
  public void tearDown() throws Exception {
    ServiceRunner.portfolioClient.kill();
    ServiceRunner.accountingClient.kill();
    ServiceRunner.customerClient.kill();
    ServiceRunner.officeClient.kill();
    ServiceRunner.identityService.kill();
    ServiceRunner.provisionerService.kill();

    ServiceRunner.embeddedMariaDb.stop();

    EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
  }

  @Test
  public void startDevServer() throws Exception {
    this.createAdmin(this.provisionAppsViaSeshat());

    System.out.println("Identity Service: " + ServiceRunner.identityService.getProcessEnvironment().serverURI());
    System.out.println("Office Service: " + ServiceRunner.officeClient.getProcessEnvironment().serverURI());
    System.out.println("Customer Service: " + ServiceRunner.customerClient.getProcessEnvironment().serverURI());
    System.out.println("Accounting Service: " + ServiceRunner.accountingClient.getProcessEnvironment().serverURI());
    System.out.println("Portfolio Service: " + ServiceRunner.portfolioClient.getProcessEnvironment().serverURI());

    boolean run = true;

    while (run) {
      final Scanner scanner = new Scanner(System.in);
      final String nextLine = scanner.nextLine();
      if (nextLine != null && nextLine.equals("exit")) {
        run = false;
      }
    }
  }

  public PublicKey getPublicKey() {
    final Signature sig = ServiceRunner.identityService.api().getSignature();

    return new RsaPublicKeyBuilder()
        .setPublicKeyMod(sig.getPublicKeyMod())
        .setPublicKeyExp(sig.getPublicKeyExp())
        .build();
  }

  private <T> Microservice<T> startService(final Class<T> serviceClass, final String serviceName) throws Exception {
    final Microservice<T> microservice = new Microservice<>(serviceClass, serviceName, "0.1.0-BUILD-SNAPSHOT", ServiceRunner.INTEGRATION_TEST_ENVIRONMENT);
    microservice.getProcessEnvironment().setProperty("server.max-http-header-size", Integer.toString(16 * 1024));
    microservice.start();
    microservice.setApiFactory(this.apiFactory);
    return microservice;
  }

  private String provisionAppsViaSeshat() throws InterruptedException {
    final AuthenticationResponse authenticationResponse =
        ServiceRunner.provisionerService.api().authenticate(ServiceRunner.CLIENT_ID, ApiConstants.SYSTEM_SU, "oS/0IiAME/2unkN1momDrhAdNKOhGykYFH/mJN20");

    String tenantAdminPassword;

    try (final AutoSeshat ignored = new AutoSeshat(authenticationResponse.getToken())) {
      final Tenant tenant = this.makeTenant();

      ServiceRunner.provisionerService.api().createTenant(tenant);

      final Application identityApplication = new Application();
      identityApplication.setName(ServiceRunner.identityService.name());
      identityApplication.setHomepage(ServiceRunner.identityService.uri());
      identityApplication.setDescription("Identity Service");
      identityApplication.setVendor("Apache Fineract");
      ServiceRunner.provisionerService.api().createApplication(identityApplication);

      final AssignedApplication assignedApplication = new AssignedApplication();
      assignedApplication.setName(ServiceRunner.identityService.name());

      final IdentityManagerInitialization identityManagerInitialization = ServiceRunner.provisionerService.api().assignIdentityManager(tenant.getIdentifier(), assignedApplication);
      tenantAdminPassword = identityManagerInitialization.getAdminPassword();

      this.createApplication(tenant, ServiceRunner.officeClient, io.mifos.office.api.v1.EventConstants.INITIALIZE);
      this.createApplication(tenant, ServiceRunner.customerClient, io.mifos.customer.api.v1.CustomerEventConstants.INITIALIZE);
      this.createApplication(tenant, ServiceRunner.accountingClient, io.mifos.accounting.api.v1.EventConstants.INITIALIZE);
      this.createApplication(tenant, ServiceRunner.portfolioClient, io.mifos.portfolio.api.v1.events.EventConstants.INITIALIZE);
    }

    return tenantAdminPassword;
  }

  private void createApplication(final Tenant tenant, final Microservice<?> microservice, final String eventType)
      throws InterruptedException {
    final Application application = new Application();
    application.setName(microservice.name());
    application.setHomepage(microservice.uri());
    application.setVendor("Apache Fineract");

    ServiceRunner.provisionerService.api().createApplication(application);

    final AssignedApplication assignedApplication = new AssignedApplication();
    assignedApplication.setName(microservice.name());
    ServiceRunner.provisionerService.api().assignApplications(tenant.getIdentifier(), Collections.singletonList(assignedApplication));

    Assert.assertTrue(this.eventRecorder.wait(eventType, eventType));
  }

  private Tenant makeTenant() {
    final Tenant tenant = new Tenant();
    tenant.setName("Apache Fineract Demo Server");
    tenant.setIdentifier(TenantContextHolder.checkedGetIdentifier());
    tenant.setDescription("All in one Demo Server");

    final CassandraConnectionInfo cassandraConnectionInfo = new CassandraConnectionInfo();
    cassandraConnectionInfo.setClusterName("Test Cluster");
    cassandraConnectionInfo.setContactPoints("127.0.0.1:9142");
    cassandraConnectionInfo.setKeyspace("fineract_demo");
    cassandraConnectionInfo.setReplicas("3");
    cassandraConnectionInfo.setReplicationType("Simple");
    tenant.setCassandraConnectionInfo(cassandraConnectionInfo);

    final DatabaseConnectionInfo databaseConnectionInfo = new DatabaseConnectionInfo();
    databaseConnectionInfo.setDriverClass("org.mariadb.jdbc.Driver");
    databaseConnectionInfo.setDatabaseName("fineract_demo");
    databaseConnectionInfo.setHost("localhost");
    databaseConnectionInfo.setPort("3306");
    databaseConnectionInfo.setUser("root");
    databaseConnectionInfo.setPassword("mysql");
    tenant.setDatabaseConnectionInfo(databaseConnectionInfo);
    return tenant;
  }

  private void createAdmin(final String tenantAdminPassword) throws Exception {
    final String tenantAdminUser = "antony";
    final Authentication adminPasswordOnlyAuthentication = ServiceRunner.identityService.api().login(tenantAdminUser, tenantAdminPassword);
    try (final AutoUserContext ignored = new AutoUserContext(tenantAdminUser, adminPasswordOnlyAuthentication.getAccessToken()))
    {
      ServiceRunner.identityService.api().changeUserPassword(tenantAdminUser, new Password(tenantAdminPassword));
      Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_PUT_USER_PASSWORD, tenantAdminUser));
    }
    final Authentication adminAuthentication = ServiceRunner.identityService.api().login(tenantAdminUser, tenantAdminPassword);

    try (final AutoUserContext ignored = new AutoUserContext(tenantAdminUser, adminAuthentication.getAccessToken())) {
      final Role fimsAdministratorRole = makeFimsAdministratorRole();

      ServiceRunner.identityService.api().createRole(fimsAdministratorRole);
      Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_ROLE, fimsAdministratorRole.getIdentifier()));

      final UserWithPassword fimsAdministratorUser = new UserWithPassword();
      fimsAdministratorUser.setIdentifier("fims");
      fimsAdministratorUser.setPassword(Base64Utils.encodeToString("p@s$w0r&".getBytes()));
      fimsAdministratorUser.setRole(fimsAdministratorRole.getIdentifier());

      ServiceRunner.identityService.api().createUser(fimsAdministratorUser);
      Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_USER, fimsAdministratorUser.getIdentifier()));

      ServiceRunner.identityService.api().logout();
    }
  }

  private Role makeFimsAdministratorRole() {
    final Permission employeeAllPermission = new Permission();
    employeeAllPermission.setAllowedOperations(AllowedOperation.ALL);
    employeeAllPermission.setPermittableEndpointGroupIdentifier(io.mifos.office.api.v1.PermittableGroupIds.EMPLOYEE_MANAGEMENT);

    final Permission officeAllPermission = new Permission();
    officeAllPermission.setAllowedOperations(AllowedOperation.ALL);
    officeAllPermission.setPermittableEndpointGroupIdentifier(io.mifos.office.api.v1.PermittableGroupIds.OFFICE_MANAGEMENT);

    final Permission userAllPermission = new Permission();
    userAllPermission.setAllowedOperations(AllowedOperation.ALL);
    userAllPermission.setPermittableEndpointGroupIdentifier(io.mifos.identity.api.v1.PermittableGroupIds.IDENTITY_MANAGEMENT);

    final Permission roleAllPermission = new Permission();
    roleAllPermission.setAllowedOperations(AllowedOperation.ALL);
    roleAllPermission.setPermittableEndpointGroupIdentifier(io.mifos.identity.api.v1.PermittableGroupIds.ROLE_MANAGEMENT);

    final Permission selfManagementPermission = new Permission();
    selfManagementPermission.setAllowedOperations(AllowedOperation.ALL);
    selfManagementPermission.setPermittableEndpointGroupIdentifier(io.mifos.identity.api.v1.PermittableGroupIds.SELF_MANAGEMENT);

    final Role role = new Role();
    role.setIdentifier("fims_administrator");
    role.setPermissions(
        Arrays.asList(
            employeeAllPermission,
            officeAllPermission,
            userAllPermission,
            roleAllPermission,
            selfManagementPermission
        )
    );

    return role;
  }
}