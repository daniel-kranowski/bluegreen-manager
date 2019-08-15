package bluegreen.manager.tasks;

import org.apache.commons.lang3.StringUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.amazonaws.services.elasticloadbalancing.model.InstanceState;

import bluegreen.manager.client.aws.ElbClient;
import bluegreen.manager.client.aws.ElbInstanceState;
import static bluegreen.manager.client.aws.ElbInstanceState.IN_SERVICE;
import static bluegreen.manager.client.aws.ElbInstanceState.OUT_OF_SERVICE;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ElbInstanceHealthProgressCheckerTest
{
  private static final String LOG_CONTEXT = "(Log Context) ";
  private static final int WAIT_NUM = 1;
  private static final String ELB_NAME = "the-load-balancer";
  private static final String EC2_INSTANCE_ID = "i-123456";
  private static final String ANOTHER_EC2_INSTANCE_ID = "i-234567";

  @Mock
  private ElbClient mockElbClient;

  private ElbInstanceHealthProgressChecker progressChecker;

  @Before
  public void makeProgressChecker()
  {
    progressChecker = new ElbInstanceHealthProgressChecker(ELB_NAME, EC2_INSTANCE_ID, LOG_CONTEXT, mockElbClient);
  }

  @Test
  public void testGetDescription()
  {
    assertTrue(StringUtils.isNotBlank(progressChecker.getDescription()));
  }

  private InstanceState makeInstanceState(String instanceId, ElbInstanceState elbInstanceState)
  {
    InstanceState instanceState = new InstanceState();
    instanceState.setInstanceId(instanceId);
    instanceState.setState(elbInstanceState.toString());
    return instanceState;
  }

  private void setupMock(InstanceState fakeInstanceState)
  {
    when(mockElbClient.describeInstanceHealth(ELB_NAME, EC2_INSTANCE_ID)).thenReturn(fakeInstanceState);
  }

  /**
   * Fail: aws elb reports status of wrong instance.
   */
  @Test(expected = IllegalStateException.class)
  public void testInitialCheck_WrongId()
  {
    setupMock(makeInstanceState(ANOTHER_EC2_INSTANCE_ID, IN_SERVICE));
    progressChecker.initialCheck();
  }

  /**
   * Already in-service: done.
   */
  @Test
  public void testInitialCheck_InService()
  {
    setupMock(makeInstanceState(EC2_INSTANCE_ID, IN_SERVICE));
    progressChecker.initialCheck();
    assertTrue(progressChecker.isDone());
    assertNotNull(progressChecker.getResult());
  }

  /**
   * Not yet in service: not done.
   */
  @Test
  public void testInitialCheck_OutOfService()
  {
    setupMock(makeInstanceState(EC2_INSTANCE_ID, OUT_OF_SERVICE));
    progressChecker.initialCheck();
    assertFalse(progressChecker.isDone());
    assertNull(progressChecker.getResult());
  }

  /**
   * Fail: aws elb reports status of wrong instance.
   */
  @Test(expected = IllegalStateException.class)
  public void testFollowupCheck_WrongId()
  {
    setupMock(makeInstanceState(ANOTHER_EC2_INSTANCE_ID, IN_SERVICE));
    progressChecker.followupCheck(WAIT_NUM);
  }

  /**
   * Already in-service: done.
   */
  @Test
  public void testFollowupCheck_InService()
  {
    setupMock(makeInstanceState(EC2_INSTANCE_ID, IN_SERVICE));
    progressChecker.followupCheck(WAIT_NUM);
    assertTrue(progressChecker.isDone());
    assertNotNull(progressChecker.getResult());
  }

  /**
   * Not yet in service: not done.
   */
  @Test
  public void testFollowupCheck_OutOfService()
  {
    setupMock(makeInstanceState(EC2_INSTANCE_ID, OUT_OF_SERVICE));
    progressChecker.followupCheck(WAIT_NUM);
    assertFalse(progressChecker.isDone());
    assertNull(progressChecker.getResult());
  }

}