package org.jenkinsci.plugins.envinject;

import hudson.*;
import hudson.matrix.MatrixRun;
import hudson.model.*;
import hudson.model.listeners.RunListener;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.util.LogTaskListener;
import org.jenkinsci.lib.envinject.EnvInjectException;
import org.jenkinsci.lib.envinject.EnvInjectLogger;
import org.jenkinsci.plugins.envinject.model.EnvInjectJobPropertyContributor;
import org.jenkinsci.plugins.envinject.service.EnvInjectActionSetter;
import org.jenkinsci.plugins.envinject.service.EnvInjectEnvVars;
import org.jenkinsci.plugins.envinject.service.EnvInjectGlobalPasswordRetriever;
import org.jenkinsci.plugins.envinject.service.EnvInjectVariableGetter;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Gregory Boissinot
 */
@Extension
public class EnvInjectListener extends RunListener<Run> implements Serializable {

    private static Logger LOG = Logger.getLogger(EnvInjectListener.class.getName());

    @Override
    public Environment setUpEnvironment(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
        EnvInjectVariableGetter variableGetter = new EnvInjectVariableGetter();
        EnvInjectLogger logger = new EnvInjectLogger(listener);
        try {
            if (!isMatrixRun(build)) {
                if (variableGetter.isEnvInjectJobPropertyActive(build)) {
                    return setUpEnvironmentNonMatrixProject(build, launcher, listener);
                }
            } else {
                if (variableGetter.isEnvInjectJobPropertyActive(build)) {
                    return setUpEnvironmentMatrixProject(build, listener);
                }
            }
        } catch (EnvInjectException envEx) {
            logger.error("SEVERE ERROR occurs: " + envEx.getMessage());
            throw new Run.RunnerAbortedException();
        } catch (Run.RunnerAbortedException rre) {
            logger.info("Fail the build.");
            throw new Run.RunnerAbortedException();
        } catch (Throwable throwable) {
            logger.error("SEVERE ERROR occurs: " + throwable.getMessage());
            throw new Run.RunnerAbortedException();
        }
        return new Environment() {
        };
    }

    private Environment setUpEnvironmentNonMatrixProject(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException, EnvInjectException {
        EnvInjectVariableGetter variableGetter = new EnvInjectVariableGetter();
        EnvInjectLogger logger = new EnvInjectLogger(listener);
        logger.info("Preparing an environment for the job.");
        EnvInjectJobProperty envInjectJobProperty = variableGetter.getEnvInjectJobProperty(build.getParent());
        assert envInjectJobProperty != null;
        EnvInjectJobPropertyInfo info = envInjectJobProperty.getInfo();
        assert envInjectJobProperty != null && envInjectJobProperty.isOn();

        Map<String, String> infraEnvVarsNode = new LinkedHashMap<String, String>();
        Map<String, String> infraEnvVarsMaster = new LinkedHashMap<String, String>();

        //Add Jenkins System variables
        if (envInjectJobProperty.isKeepJenkinsSystemVariables()) {
            logger.info("Jenkins system variables are kept.");
            infraEnvVarsMaster.putAll(getJenkinsSystemVariables(build, true));
            infraEnvVarsNode.putAll(getJenkinsSystemVariables(build, false));
        }

        //Add build variables
        if (envInjectJobProperty.isKeepBuildVariables()) {
            logger.info("Jenkins build variables are kept.");
            Map<String, String> buildVariables = variableGetter.getBuildVariables(build, logger);
            infraEnvVarsMaster.putAll(buildVariables);
            infraEnvVarsNode.putAll(buildVariables);
        }

        //Add build parameters (or override)
        Map<String, String> parametersVariables = variableGetter.overrideParametersVariablesWithSecret(build);
        infraEnvVarsNode.putAll(parametersVariables);

        final FilePath rootPath = getNodeRootPath();
        if (rootPath != null) {

            EnvInjectEnvVars envInjectEnvVarsService = new EnvInjectEnvVars(logger);

            //Execute script
            int resultCode = envInjectEnvVarsService.executeScript(info.isLoadFilesFromMaster(),
                    info.getScriptContent(),
                    rootPath, info.getScriptFilePath(), infraEnvVarsMaster, infraEnvVarsNode, launcher, listener);
            if (resultCode != 0) {
                build.setResult(Result.FAILURE);
                throw new Run.RunnerAbortedException();
            }

            final Map<String, String> propertiesVariables = envInjectEnvVarsService.getEnvVarsPropertiesJobProperty(rootPath,
                    logger, info.isLoadFilesFromMaster(),
                    info.getPropertiesFilePath(), info.getPropertiesContentMap(),
                    infraEnvVarsMaster, infraEnvVarsNode);

            //Get variables get by contribution
            Map<String, String> contributionVariables = getEnvVarsByContribution(build, envInjectJobProperty, listener);

            final Map<String, String> resultVariables = envInjectEnvVarsService.getMergedVariables(infraEnvVarsNode, propertiesVariables, contributionVariables);

            //Add an action
            new EnvInjectActionSetter(rootPath).addEnvVarsToEnvInjectBuildAction(build, resultVariables);

            return new Environment() {
                @Override
                public void buildEnvVars(Map<String, String> env) {
                    env.putAll(resultVariables);
                }
            };
        }
        return new Environment() {
        };
    }

    private Environment setUpEnvironmentMatrixProject(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException, EnvInjectException {
        EnvInjectVariableGetter variableGetter = new EnvInjectVariableGetter();
        EnvInjectLogger logger = new EnvInjectLogger(listener);
        logger.info("Using environment variables injected by the matrix job.");
        final Map<String, String> resultVariables = variableGetter.getEnvVarsPreviousSteps(build, logger);
        final FilePath rootPath = getNodeRootPath();

        if (rootPath != null) {
            //Add an action
            new EnvInjectActionSetter(rootPath).addEnvVarsToEnvInjectBuildAction(build, resultVariables);
        }
        return new Environment() {
            @Override
            public void buildEnvVars(Map<String, String> env) {
                env.putAll(resultVariables);
            }
        };
    }

    private boolean isMatrixRun(AbstractBuild build) {
        return build instanceof MatrixRun;
    }

    private Map<String, String> getJenkinsSystemVariables(AbstractBuild build, boolean onMaster) throws IOException, InterruptedException {

        Map<String, String> result = new TreeMap<String, String>();

        Computer computer;
        if (onMaster) {
            computer = Hudson.getInstance().toComputer();
        } else {
            computer = Computer.currentComputer();
        }

        //test if there is at least one executor
        if (computer != null) {
            result = computer.getEnvironment().overrideAll(result);
            Node n = computer.getNode();
            if (n != null)
                result.put("NODE_NAME", computer.getName());
            result.put("NODE_LABELS", Util.join(n.getAssignedLabels(), " "));

            Executor e = build.getExecutor();
            if (e != null) {
                result.put("EXECUTOR_NUMBER", String.valueOf(e.getNumber()));
            }
        }

        String rootUrl = Hudson.getInstance().getRootUrl();
        if (rootUrl != null) {
            result.put("JENKINS_URL", rootUrl);
            result.put("HUDSON_URL", rootUrl); // Legacy compatibility
            result.put("BUILD_URL", rootUrl + build.getUrl());
            result.put("JOB_URL", rootUrl + build.getParent().getUrl());
        }
        result.put("JENKINS_HOME", Hudson.getInstance().getRootDir().getPath());
        result.put("HUDSON_HOME", Hudson.getInstance().getRootDir().getPath());   // legacy compatibility

        EnvVars envVars = new EnvVars();
        for (EnvironmentContributor ec : EnvironmentContributor.all()) {
            ec.buildEnvironmentFor(build, envVars, new LogTaskListener(LOG, Level.ALL));
            result.putAll(envVars);
        }

        //Global properties
        for (NodeProperty<?> nodeProperty : Hudson.getInstance().getGlobalNodeProperties()) {
            if (nodeProperty instanceof EnvironmentVariablesNodeProperty) {
                EnvironmentVariablesNodeProperty environmentVariablesNodeProperty = (EnvironmentVariablesNodeProperty) nodeProperty;
                result.putAll(environmentVariablesNodeProperty.getEnvVars());
            }
        }

        //Node properties
        if (computer != null) {
            Node node = computer.getNode();
            if (node != null) {
                for (NodeProperty<?> nodeProperty : node.getNodeProperties()) {
                    if (nodeProperty instanceof EnvironmentVariablesNodeProperty) {
                        EnvironmentVariablesNodeProperty environmentVariablesNodeProperty = (EnvironmentVariablesNodeProperty) nodeProperty;
                        result.putAll(environmentVariablesNodeProperty.getEnvVars());
                    }
                }
            }
        }
        return result;
    }

    private Node getNode() {
        Computer computer = Computer.currentComputer();
        if (computer == null) {
            return null;
        }
        return computer.getNode();
    }

    private FilePath getNodeRootPath() {
        Node node = getNode();
        if (node != null) {
            return node.getRootPath();
        }
        return null;
    }

    private boolean parameter2exclude(EnvironmentContributingAction a) {

        if ((EnvInjectBuilder.ENVINJECT_BUILDER_ACTION_NAME).equals(a.getDisplayName())) {
            return true;
        }

        if (a instanceof ParametersAction) {
            return true;
        }

        return false;
    }

    private Map<String, String> getEnvVarsByContribution(AbstractBuild build, EnvInjectJobProperty envInjectJobProperty, BuildListener listener) throws EnvInjectException {

        assert envInjectJobProperty != null;
        Map<String, String> contributionVariables = new HashMap<String, String>();

        EnvInjectJobPropertyContributor[] contributors = envInjectJobProperty.getContributors();
        if (contributors != null) {
            for (EnvInjectJobPropertyContributor contributor : contributors) {
                contributionVariables.putAll(contributor.getEnvVars(build, listener));
            }
        }
        return contributionVariables;
    }

    @Override
    public void onCompleted(Run run, TaskListener listener) {

        EnvVars envVars = new EnvVars();
        EnvInjectLogger logger = new EnvInjectLogger(listener);

        EnvInjectPluginAction envInjectAction = run.getAction(EnvInjectPluginAction.class);
        if (envInjectAction != null) {
            //Add other plugins env vars contribution variables (exclude builder action and parameter actions already populated)
            for (EnvironmentContributingAction a : Util.filter(run.getActions(), EnvironmentContributingAction.class)) {
                if (!parameter2exclude(a)) {
                    a.buildEnvVars((AbstractBuild<?, ?>) run, envVars);
                }
            }
        } else {
            //Keep classic injected env vars
            AbstractBuild abstractBuild = (AbstractBuild) run;
            try {
                envVars.putAll(abstractBuild.getEnvironment(listener));
            } catch (IOException e) {
                logger.error("SEVERE ERROR occurs: " + e.getMessage());
                throw new Run.RunnerAbortedException();
            } catch (InterruptedException e) {
                logger.error("SEVERE ERROR occurs: " + e.getMessage());
                throw new Run.RunnerAbortedException();
            }
        }

        //Mask passwords
        maskGlobalPasswordsIfAny(logger, envVars);

        //Add or override EnvInject Action
        EnvInjectActionSetter envInjectActionSetter = new EnvInjectActionSetter(getNodeRootPath());
        try {
            envInjectActionSetter.addEnvVarsToEnvInjectBuildAction((AbstractBuild<?, ?>) run, envVars);
        } catch (EnvInjectException e) {
            logger.error("SEVERE ERROR occurs: " + e.getMessage());
            throw new Run.RunnerAbortedException();
        } catch (IOException e) {
            logger.error("SEVERE ERROR occurs: " + e.getMessage());
            throw new Run.RunnerAbortedException();
        } catch (InterruptedException e) {
            logger.error("SEVERE ERROR occurs: " + e.getMessage());
            throw new Run.RunnerAbortedException();
        }
    }

    private void maskGlobalPasswordsIfAny(EnvInjectLogger logger, Map<String, String> envVars) {
        try {
            EnvInjectGlobalPasswordRetriever globalPasswordRetriever = new EnvInjectGlobalPasswordRetriever();
            EnvInjectGlobalPasswordEntry[] passwordEntries = globalPasswordRetriever.getGlobalPasswords();
            if (passwordEntries != null) {
                for (EnvInjectGlobalPasswordEntry globalPasswordEntry : passwordEntries) {
                    envVars.put(globalPasswordEntry.getName(),
                            globalPasswordEntry.getValue().getEncryptedValue());
                }
            }
        } catch (EnvInjectException ee) {
            logger.error("Can't mask global password :" + ee.getMessage());
        }
    }


}
