package bluegreen.manager.tasks;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.amazonaws.services.rds.model.DBInstance;

import bluegreen.manager.client.aws.RdsAnalyzer;
import bluegreen.manager.client.aws.RdsClient;
import bluegreen.manager.client.aws.RdsClientFactory;
import bluegreen.manager.client.aws.RdsInstanceStatus;
import bluegreen.manager.model.domain.Environment;
import bluegreen.manager.model.domain.LogicalDatabase;
import bluegreen.manager.model.domain.PhysicalDatabase;
import bluegreen.manager.model.domain.TaskStatus;
import bluegreen.manager.model.tx.EnvLoaderFactory;
import bluegreen.manager.model.tx.EnvironmentTx;
import bluegreen.manager.model.tx.OneEnvLoader;
import bluegreen.manager.utils.ThreadSleeper;
import bluegreen.manager.utils.Waiter;
import bluegreen.manager.utils.WaiterParameters;

/**
 * In the delete env, deletes the RDS instance, its parameter group (if non-default), and the bluegreen snapshot from
 * which it was originally made.
 * <p/>
 * Only deletes the parameter group when it is clear that stagingDeploy created it specifically for the RDS instance
 * that we're deleting.
 * <p/>
 * Automated deletion is DANGEROUS.  This task needs to be extremely well maintained and tested.  Our only safety net
 * is the "isLive" flag: we only delete non-live instances.
 */
@Lazy
@Component
public class RdsInstanceDeleteTask extends TaskImpl
{
  private static final Logger LOGGER = LoggerFactory.getLogger(RdsInstanceDeleteTask.class);

  @Autowired
  @Qualifier("rdsInstanceDeleteTask")
  private WaiterParameters waiterParameters;

  @Autowired
  private EnvironmentTx environmentTx;

  @Autowired
  private EnvLoaderFactory envLoaderFactory;

  @Autowired
  private RdsClientFactory rdsClientFactory;

  @Autowired
  private RdsAnalyzer rdsAnalyzer;

  @Autowired
  private ThreadSleeper threadSleeper;

  private OneEnvLoader deleteEnvLoader;
  private RdsClient rdsClient;

  private String deleteEnvName;

  private Environment deleteEnvironment;
  private LogicalDatabase deleteLogicalDatabase;
  private PhysicalDatabase deletePhysicalDatabase;

  public Task assign(int position, String deleteEnvName)
  {
    super.assign(position);
    this.deleteEnvName = deleteEnvName;
    return this;
  }

  /**
   * Loads datamodel entities and asserts preconditions on them.  These assertions should be true at the moment when
   * this task is about to begin processing.
   * <p/>
   * Looks up the environment entities by name.
   * Currently requires that the env has exactly one logicaldb, with one physicaldb.
   */
  void loadDataModel()
  {
    this.deleteEnvLoader = envLoaderFactory.createOne(deleteEnvName);
    deleteEnvLoader.loadPhysicalDatabase();
    this.deleteEnvironment = deleteEnvLoader.getEnvironment();
    this.deleteLogicalDatabase = deleteEnvLoader.getLogicalDatabase();
    this.deletePhysicalDatabase = deleteEnvLoader.getPhysicalDatabase();
  }

  String context()
  {
    StringBuilder sb = new StringBuilder();
    sb.append("[Delete Env '" + deleteEnvironment.getEnvName() + "'");
    if (deleteLogicalDatabase != null)
    {
      sb.append(", ");
      sb.append(deleteLogicalDatabase.getLogicalName());
      if (StringUtils.isNotBlank(deletePhysicalDatabase.getInstanceName()))
      {
        sb.append(" - RDS ");
        sb.append(deletePhysicalDatabase.getInstanceName());
      }
    }
    sb.append("]: ");
    return sb.toString();
  }

  /**
   * Deletes the rds instance, its parameter group (if non-default), and its original bluegreen snapshot.
   * <p/>
   * Leaves behind any snapshots that Amazon automatically made of the rds instance.
   */
  @Override
  public TaskStatus process(boolean noop)
  {
    loadDataModel();
    checkDeleteDatabaseIsNotLive();
    rdsClient = rdsClientFactory.create();
    DBInstance rdsInstance = deleteInstance(noop);
    deleteParameterGroup(rdsInstance, noop);
    persistModel(noop);
    return noop ? TaskStatus.NOOP : TaskStatus.DONE;
  }

  /**
   * Performs the single most important check of this task: asserts that the database to be deleted is not live.
   * <p/>
   * It would be Very Very Bad to delete a live database!
   */
  void checkDeleteDatabaseIsNotLive()
  {
    if (deletePhysicalDatabase.isLive())
    {
      throw new IllegalArgumentException(context() + "Are you CRAZY??? Don't ask us to delete a LIVE database!!!");
    }
  }

  /**
   * Requests deletion of the target RDS instance, waits for confirmed deletion.
   */
  DBInstance deleteInstance(boolean noop)
  {
    LOGGER.info(context() + "Deleting non-live target RDS instance" + noopRemark(noop));
    DBInstance initialInstance = null;
    if (!noop)
    {
      initialInstance = rdsClient.deleteInstance(deletePhysicalDatabase.getInstanceName());
      waitTilInstanceIsDeleted(initialInstance);
    }
    return initialInstance;
  }

  /**
   * Creates a Waiter and returns when the instance is fully deleted.
   */
  private void waitTilInstanceIsDeleted(DBInstance initialInstance)
  {
    LOGGER.info(context() + "Waiting for instance to be deleted");
    RdsInstanceProgressChecker progressChecker = new RdsInstanceProgressChecker(initialInstance.getDBInstanceIdentifier(),
        context(), rdsClient, initialInstance, RdsInstanceStatus.DELETING);
    Waiter<DBInstance> waiter = new Waiter(waiterParameters, threadSleeper, progressChecker);
    DBInstance dbInstance = waiter.waitTilDone();
    if (dbInstance == null)
    {
      throw new RuntimeException(context() + progressChecker.getDescription() + " was not deleted");
    }
  }

  /**
   * Deletes the parameter group, if it appears to have been created solely for the deleted db instance.
   * <p/>
   * (The parameter group cannot be deleted until the dependent rdsInstance is fully deleted.)
   */
  void deleteParameterGroup(DBInstance rdsInstance, boolean noop)
  {
    if (noop)
    {
      //rdsInstance is null, don't try to analyze it
      LOGGER.info(context() + "Deleting parameter group" + noopRemark(noop));
    }
    else
    {
      String paramGroupName = rdsAnalyzer.findSelfNamedParamGroupName(rdsInstance);
      if (StringUtils.isBlank(paramGroupName))
      {
        LOGGER.info(context() + "Deleted database did not have its own special parameter group");
      }
      else
      {
        LOGGER.info(context() + "Deleting parameter group '" + paramGroupName + "', which was used only by the deleted database");
        rdsClient.deleteParameterGroup(paramGroupName);
      }
    }
  }

  /**
   * Deletes the physicaldb entity, then opens a transaction to persist the change.
   */
  private void persistModel(boolean noop)
  {
    LOGGER.info(context() + "Unregistering stage physical database" + noopRemark(noop));
    if (!noop)
    {
      deleteLogicalDatabase.setPhysicalDatabase(null);
      environmentTx.updateEnvironment(deleteEnvironment); //Cascades to delete physicaldb.
    }
  }

}
