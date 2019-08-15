package bluegreen.manager.tasks;

import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import bluegreen.manager.client.app.DbFreezeMode;
import bluegreen.manager.client.app.DbFreezeProgress;
import bluegreen.manager.model.domain.TaskStatus;
import bluegreen.manager.utils.ThreadSleeper;
import bluegreen.manager.utils.Waiter;
import bluegreen.manager.utils.WaiterParameters;

/**
 * Transitions the apps in the requested environment to the next dbfreeze-related steady state.
 */
public abstract class TransitionTask extends ApplicationTask
{
  private static final Logger LOGGER = LoggerFactory.getLogger(TransitionTask.class);

  @Autowired
  @Qualifier("transitionTask")
  private WaiterParameters waiterParameters;

  @Autowired
  private ThreadSleeper threadSleeper;

  private TransitionParameters transitionParameters;

  public abstract TransitionTask assignTransition(int position, String envName);

  protected void assign(int position, String envName, TransitionParameters transitionParameters)
  {
    super.assign(position, envName);
    this.transitionParameters = transitionParameters;
  }

  /**
   * Attempts to transition the target application, waits for it to finish.
   */
  @Override
  public TaskStatus process(boolean noop)
  {
    TaskStatus taskStatus = TaskStatus.ERROR;
    loadDataModel();
    initApplicationSession();
    if (appIsReadyToTransition())
    {
      TransitionProgressChecker progressChecker = requestTransition(noop);
      if (noop || !progressChecker.isDone())
      {
        if (waitForTransition(progressChecker, noop))
        {
          taskStatus = noop ? TaskStatus.NOOP : TaskStatus.DONE;
        }
      }
    }
    return taskStatus;
  }

  /**
   * Returns true if the application is ready to make the transition.
   * <p/>
   * Read-only so runs even if noop.
   */
  boolean appIsReadyToTransition()
  {
    LOGGER.info(context() + "Checking if application is ready to " + transitionParameters.getVerb());
    DbFreezeProgress dbFreezeProgress = applicationClient.getDbFreezeProgress(application, applicationSession, null);
    LOGGER.debug(context() + "Application response: " + dbFreezeProgress);
    boolean isReady = false;
    if (dbFreezeProgress == null)
    {
      LOGGER.error(context() + "Null application response");
    }
    else if (dbFreezeProgress.isLockError())
    {
      LOGGER.error(context() + "Application responded with a lock error: " + dbFreezeProgress);
    }
    else
    {
      DbFreezeMode mode = dbFreezeProgress.getMode();
      if (!isAllowedStartMode(mode))
      {
        LOGGER.error(context() + "Mode '" + mode + "' indicates application is not ready to "
            + transitionParameters.getVerb() + ".  Progress: " + dbFreezeProgress);
      }
      else
      {
        isReady = true;
      }
    }
    return isReady;
  }

  /**
   * True if the transition parameters allow the transition to start from the specified mode.
   */
  boolean isAllowedStartMode(DbFreezeMode mode)
  {
    return ArrayUtils.contains(transitionParameters.getAllowedStartModes(), mode);
  }

  /**
   * Requests that the application do the transition.
   *
   * @param noop If true, don't contact the application.
   * @return Transition progress checker, or null if noop.
   */
  TransitionProgressChecker requestTransition(boolean noop)
  {
    LOGGER.info(context() + "Requesting a " + transitionParameters.getVerb() + noopRemark(noop));
    TransitionProgressChecker progressChecker = null;
    if (!noop)
    {
      final int waitNum = 0;
      DbFreezeProgress initialProgress = applicationClient.putRequestTransition(application, applicationSession,
          transitionParameters.getTransitionMethodPath(), waitNum);
      progressChecker = new TransitionProgressChecker(transitionParameters, context(), initialProgress,
          applicationClient, applicationSession, application);
    }
    return progressChecker;
  }

  /**
   * Waits for the application to finish the requested transition.
   *
   * @param noop If true, don't contact the application.
   * @return True if the application has reached destination mode prior to timeout, or if noop.
   * False if error or other failure to make transition.
   */
  Boolean waitForTransition(TransitionProgressChecker progressChecker, boolean noop)
  {
    LOGGER.info(context() + "Waiting for " + transitionParameters.getVerb() + " to take effect" + noopRemark(noop));
    if (!noop)
    {
      Waiter<Boolean> waiter = new Waiter(waiterParameters, threadSleeper, progressChecker);
      return waiter.waitTilDone();
    }
    return true;
  }

  // Test purposes only
  public TransitionParameters getTransitionParameters()
  {
    return transitionParameters;
  }
}
