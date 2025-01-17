package com.khmelyuk.multirun;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.ExecutionTarget;
import com.intellij.execution.ExecutionTargetManager;
import com.intellij.execution.Executor;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.RunnerRegistry;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.impl.RunDialog;
import com.intellij.execution.impl.RunManagerImpl;
import com.intellij.execution.impl.RunnerAndConfigurationSettingsImpl;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.impl.ActionManagerImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.LayeredIcon;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Ruslan Khmelyuk
 */
public class MultirunRunnerState implements RunProfileState {

    private final double delayTime;
    private final boolean reuseTabs;
    private final boolean reuseTabsWithFailure;
    private final boolean startOneByOne;
    private final boolean markFailedProcess;
    private final boolean hideSuccessProcess;
    private final List<RunConfiguration> runConfigurations;
    private final StopRunningMultirunConfigurationsAction stopRunningMultirunConfiguration;

    public MultirunRunnerState(List<RunConfiguration> runConfigurations,
                               boolean startOneByOne, double delayTime,
                               boolean reuseTabs, boolean reuseTabsWithFailure,
                               boolean markFailedProcess, boolean hideSuccessProcess) {

        this.delayTime = delayTime;
        this.reuseTabs = reuseTabs;
        this.reuseTabsWithFailure = reuseTabsWithFailure;
        this.startOneByOne = startOneByOne;
        this.runConfigurations = runConfigurations;
        this.markFailedProcess = markFailedProcess;
        this.hideSuccessProcess = hideSuccessProcess;

        ActionManager actionManager = ActionManagerImpl.getInstance();
        stopRunningMultirunConfiguration = (StopRunningMultirunConfigurationsAction) actionManager.getAction("stopRunningMultirunConfiguration");
    }

    @Nullable
    @Override
    public ExecutionResult execute(Executor executor, @NotNull ProgramRunner programRunner) {
        stopRunningMultirunConfiguration.beginStartingConfigurations();
        ApplicationManager.getApplication().executeOnPooledThread(() -> runConfigurations(executor, runConfigurations, 0));

        return null;
    }

    private void runConfigurations(final Executor executor, final List<RunConfiguration> runConfigurations, final int index) {
        if (index >= runConfigurations.size()) {
            stopRunningMultirunConfiguration.doneStaringConfigurations();
            return;
        }
        if (!stopRunningMultirunConfiguration.canContinueStartingConfigurations()) {
            stopRunningMultirunConfiguration.doneStaringConfigurations();
            // don't start more configurations if user stopped the plugin work.
            return;
        }

        final RunConfiguration runConfiguration = runConfigurations.get(index);
        final Project project = runConfiguration.getProject();
        final RunnerAndConfigurationSettings configuration = new RunnerAndConfigurationSettingsImpl(
                RunManagerImpl.getInstanceImpl(project), runConfiguration, false);

        boolean started = false;
        try {
            final ProgramRunner runner = RunnerRegistry.getInstance().getRunner(executor.getId(), runConfiguration);
            if (runner == null) {return;}
            if (!checkRunConfiguration(executor, project, configuration)) {return;}

            final ExecutionEnvironment executionEnvironment = new ExecutionEnvironment(executor, runner, configuration, project);

            executionEnvironment.setCallback(
                    new ProgramRunner.Callback() {
                        private final AtomicBoolean processTerminated = new AtomicBoolean(false);

                        @SuppressWarnings("ConstantConditions")
                        @Override
                        public void processStarted(final RunContentDescriptor descriptor) {
                            if (descriptor == null) {
                                if (startOneByOne) {
                                    // start next configuration..
                                    ApplicationManager.getApplication().executeOnPooledThread(
                                            () -> runConfigurations(executor, runConfigurations, index + 1));
                                }
                                return;
                            }

                            final ProcessHandler processHandler = descriptor.getProcessHandler();
                            if (processHandler != null) {
                                processHandler.addProcessListener(new ProcessAdapter() {
                                    @SuppressWarnings("ConstantConditions")
                                    @Override
                                    public void startNotified(ProcessEvent processEvent) {
                                        Content content = descriptor.getAttachedContent();
                                        if (content != null) {
                                            content.setIcon(descriptor.getIcon());
                                            if (!stopRunningMultirunConfiguration.canContinueStartingConfigurations()) {
                                                // Multirun was stopped - destroy processes that are still starting up
                                                processHandler.destroyProcess();

                                                if (!content.isPinned() && !startOneByOne) {
                                                    // checks if not pinned, to avoid destroying already existed tab
                                                    // checks if start one by one - no need to close the console tab, as it's won't be shown
                                                    // as other checks disallow starting it

                                                    // content.getManager() can be null, if content is removed already as part of destroy above
                                                    if (content.getManager() != null) {
                                                        content.getManager().removeContent(content, false);
                                                    }
                                                }
                                            } else {
                                                // ensure tab is not pinned
                                                content.setPinned(false);

                                                // mark running process tab with *
                                                content.setDisplayName(descriptor.getDisplayName() + "*");
                                            }
                                        }
                                    }

                                    @Override
                                    public void processTerminated(final ProcessEvent processEvent) {
                                        onTermination(processEvent, true);
                                        processTerminated.set(true);
                                        stopRunningMultirunConfiguration.removeProcess(project, processEvent.getProcessHandler());
                                    }

                                    @Override
                                    public void processWillTerminate(ProcessEvent processEvent, boolean willBeDestroyed) {}

                                    private void onTermination(final ProcessEvent processEvent, final boolean terminated) {
                                        if (descriptor.getAttachedContent() == null) {
                                            return;
                                        }

                                        ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                final Content content = descriptor.getAttachedContent();
                                                if (content == null) {return;}

                                                // exit code is 0 if the process completed successfully
                                                final boolean completedSuccessfully = (terminated && processEvent.getExitCode() == 0);

                                                if (hideSuccessProcess && completedSuccessfully) {
                                                    // close the tab for the success process and exit - nothing else could be done
                                                    ApplicationManager.getApplication().invokeLater(() -> {
                                                        if (content.getManager() != null) {
                                                            content.getManager().removeContent(content, false);
                                                        }
                                                    });
                                                    return;
                                                }

                                                if ((completedSuccessfully && !reuseTabs) || (!completedSuccessfully && !reuseTabsWithFailure)) {
                                                    // attempt to pin tab if not completed successfully or asked not to reuse tabs
                                                    if (!stopRunningMultirunConfiguration.isStopMultirunTriggered()) {
                                                        // ... do not pin if multirun stopped by "Stop Multirun" action.
                                                        content.setPinned(true);
                                                    }
                                                }

                                                // remove the * used to identify running process
                                                content.setDisplayName(descriptor.getDisplayName());

                                                // add the alert icon in case if process existed with non-0 status
                                                if (markFailedProcess && processEvent.getExitCode() != 0) {
                                                    ApplicationManager.getApplication().executeOnPooledThread(
                                                            () -> content.setIcon(LayeredIcon.create(content.getIcon(), AllIcons.Nodes.TabAlert)));
                                                }
                                            }
                                        });
                                    }
                                });
                            }
                            stopRunningMultirunConfiguration.addProcess(project, processHandler);

                            final boolean moreConfigurationsToRun = index + 1 < runConfigurations.size();
                            if (startOneByOne && moreConfigurationsToRun) {
                                // start next configuration..

                                if (delayTime > 0) {
                                    final long start = System.currentTimeMillis();
                                    ProgressManager.getInstance().run(new Task.Backgroundable(project, "Waiting for delay") {
                                        @Override
                                        public void run(@NotNull ProgressIndicator progressIndicator) {
                                            try {
                                                progressIndicator.setIndeterminate(false);
                                                while (System.currentTimeMillis() - start < delayTime * 1000) {
                                                    if (processTerminated.get()) {
                                                        break;
                                                    }
                                                    if (progressIndicator.isCanceled()) {
                                                        return;
                                                    }
                                                    final double passed = (double) (System.currentTimeMillis() - start) / 1000;
                                                    final String seconds = (delayTime - passed == 1) ? "second" : "seconds";
                                                    progressIndicator.setFraction(passed / delayTime);
                                                    final String waitingPeriod = String.format("%.1f", delayTime - passed);
                                                    progressIndicator.setText("waiting " + waitingPeriod + " " + seconds);
                                                    Thread.sleep(100);
                                                }
                                            } catch (InterruptedException ignored) {
                                                return;
                                            }
                                            ApplicationManager.getApplication().executeOnPooledThread(
                                                    () -> runConfigurations(executor, runConfigurations, index + 1));
                                        }
                                    });
                                } else if (delayTime < 0) {
                                    ProgressManager.getInstance().run(new Task.Backgroundable(project, "Waiting for process to complete") {
                                        @Override
                                        public void run(@NotNull ProgressIndicator progressIndicator) {
                                            try {
                                                while (!processTerminated.get()) {
                                                    if (progressIndicator.isCanceled()) {
                                                        return;
                                                    }
                                                    Thread.sleep(200);
                                                }
                                            } catch (InterruptedException ignored) {
                                                return;
                                            }
                                            ApplicationManager.getApplication().executeOnPooledThread(
                                                    () -> runConfigurations(executor, runConfigurations, index + 1));
                                        }
                                    });
                                } else {
                                    ApplicationManager.getApplication().executeOnPooledThread(
                                            () -> runConfigurations(executor, runConfigurations, index + 1));
//                                    runConfigurations(executor, runConfigurations, index + 1);
                                }
                            } else {
                                stopRunningMultirunConfiguration.doneStaringConfigurations();
                            }
                        }
                    }
            );
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    runner.execute(executionEnvironment);
                } catch (ExecutionException e) {
                    ExecutionUtil.handleExecutionError(project, executor.getToolWindowId(), configuration.getConfiguration(), e);
                }
            }, ModalityState.defaultModalityState());
            started = true;
        } finally {
            // start the next one
            if (!startOneByOne) {
                ApplicationManager.getApplication().executeOnPooledThread(
                        () -> runConfigurations(executor, runConfigurations, index + 1));
            } else if (!started) {
                // failed to start current, means the chain is broken
                ApplicationManager.getApplication().executeOnPooledThread(
                        () -> runConfigurations(executor, runConfigurations, index + 1));
            }
        }
    }

    private boolean checkRunConfiguration(Executor executor, Project project, RunnerAndConfigurationSettings configuration) {
        ExecutionTarget target = ExecutionTargetManager.getActiveTarget(project);

        if (!ExecutionTargetManager.canRun(configuration.getConfiguration(), target)) {
            ExecutionUtil.handleExecutionError(
                    project, executor.getToolWindowId(), configuration.getConfiguration(),
                    new ExecutionException(StringUtil.escapeXmlEntities("Cannot run '" + configuration.getName()
                                                                                + "' on '" + target.getDisplayName() + "'")));
            return false;
        }

        if (!RunManagerImpl.canRunConfiguration(configuration, executor) || configuration.isEditBeforeRun()) {
            if (!RunDialog.editConfiguration(project, configuration, "Edit Configuration", executor)) {
                return false;
            }

            while (!RunManagerImpl.canRunConfiguration(configuration, executor)) {
                if (0 == Messages.showYesNoDialog(project, "Configuration is still incorrect. Do you want to edit it again?",
                                                  "Change Configuration Settings",
                                                  "Edit", "Continue Anyway", Messages.getErrorIcon())) {
                    if (!RunDialog.editConfiguration(project, configuration, "Edit Configuration", executor)) {
                        break;
                    }
                } else {
                    break;
                }
            }
        }
        return true;
    }
}
