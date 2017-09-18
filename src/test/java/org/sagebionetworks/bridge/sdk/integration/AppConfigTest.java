package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;

import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.AppConfigsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.ConstraintViolationException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.AppConfig;
import org.sagebionetworks.bridge.rest.model.AppConfigList;
import org.sagebionetworks.bridge.rest.model.Criteria;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SchemaReference;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.SurveyReference;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;

import com.amazonaws.services.identitymanagement.model.User;
import com.google.common.collect.Lists;

public class AppConfigTest {
    
    private TestUser user;
    private TestUser developer;
    private TestUser admin;

    private ForConsentedUsersApi userApi;
    private AppConfigsApi adminApi;
    private AppConfigsApi devApi;
    
    private Study study;
    
    @Before
    public void before() throws IOException {
        user = TestUserHelper.createAndSignInUser(ActivityEventTest.class, true);
        developer = TestUserHelper.createAndSignInUser(ExternalIdsTest.class, false, Role.DEVELOPER);
        admin = TestUserHelper.getSignedInAdmin();
        
        userApi = user.getClient(ForConsentedUsersApi.class);
        adminApi = admin.getClient(AppConfigsApi.class);
        devApi = developer.getClient(AppConfigsApi.class);
        
        study = developer.getClient(StudiesApi.class).getUsersStudy().execute().body();
    }
    
    @After
    public void after() throws Exception {
        AppConfigList list = devApi.getAppConfigs().execute().body();
        for (AppConfig config : list.getItems()) {
            adminApi.deleteAppConfig(config.getGuid()).execute();
        }
    }
    
    @Test
    public void crudAppConfig() throws IOException {
        SchemaReference schemaRef1 = new SchemaReference().id("boo").revision(2L);
        List<SchemaReference> schemaReferences = Lists.newArrayList();
        schemaReferences.add(schemaRef1);
        
        SurveyReference surveyRef1 = new SurveyReference().guid("ABC-DEF-GHI").identifier("ya").createdOn(DateTime.now());
        List<SurveyReference> surveyReferences = Lists.newArrayList();
        surveyReferences.add(surveyRef1);

        User user = new User();
        user.setArn("test-arn");
        user.setUserId("userId");
        
        Criteria criteria = new Criteria();
        criteria.noneOfGroups(study.getDataGroups());
        
        AppConfig appConfig = new AppConfig();
        appConfig.clientData(user);
        appConfig.setCriteria(criteria);
        appConfig.setSchemaReferences(schemaReferences);
        appConfig.setSurveyReferences(surveyReferences);
        
        // Create
        GuidVersionHolder holder = devApi.createAppConfig(appConfig).execute().body();
        appConfig.setGuid(holder.getGuid());
        appConfig.setVersion(holder.getVersion());
        
        assertEquals(schemaRef1, appConfig.getSchemaReferences().get(0));
        assertEquals(surveyRef1, appConfig.getSurveyReferences().get(0));
        
        User savedUser = RestUtils.toType(appConfig.getClientData(), User.class);
        assertEquals("test-arn", savedUser.getArn());
        assertEquals("userId", savedUser.getUserId());
        
        // Change it
        appConfig.getSchemaReferences().add(schemaRef1);
        appConfig.getSurveyReferences().add(surveyRef1);
        
        // Update it. This
        holder = devApi.updateAppConfig(appConfig.getGuid(), appConfig).execute().body();
        appConfig.setGuid(holder.getGuid());
        appConfig.setVersion(holder.getVersion());

        // You can retrieve this first app config.
        AppConfig retrievedAppConfig = devApi.getAppConfig(appConfig.getGuid()).execute().body();
        assertEquals(2, retrievedAppConfig.getSchemaReferences().size());
        assertEquals(2, retrievedAppConfig.getSurveyReferences().size());
        
        // You can get it as the user (there's only one)
        AppConfig userAppConfig = userApi.getUsersAppConfig().execute().body();
        assertNotNull(userAppConfig);
        
        // Create a second app config
        devApi.createAppConfig(appConfig).execute().body();
        // Now this will generate an error...
        try {
            userApi.getUsersAppConfig().execute();
            fail("Should have thrown an exception");
        } catch(ConstraintViolationException e) {
            
        }

        StudyParticipant participant = userApi.getUsersParticipantRecord().execute().body();
        participant.setDataGroups(Lists.newArrayList(study.getDataGroups())); // this won't match.
        userApi.updateUsersParticipantRecord(participant).execute();

        try {
            userApi.getUsersAppConfig().execute();
            fail("Should have thrown an exception");
        } catch(EntityNotFoundException e) {
            // None have matched
        }
        
        appConfig.getCriteria().setNoneOfGroups(null);
        devApi.updateAppConfig(appConfig.getGuid(), appConfig).execute();
        
        // Finally... we have one, it will be returned
        AppConfig config = userApi.getUsersAppConfig().execute().body();
        assertEquals(appConfig.getGuid(), config.getGuid());
    }
}
