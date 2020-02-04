/*
 * Copyright 2004 - 2012 Mirko Nasato and contributors
 *           2016 - 2020 Simon Braconnier and contributors
 *
 * This file is part of JODConverter - Java OpenDocument Converter.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jodconverter.local.office;

import static org.jodconverter.local.process.ProcessManager.PID_NOT_FOUND;
import static org.jodconverter.local.process.ProcessManager.PID_UNKNOWN;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jodconverter.core.office.OfficeException;
import org.jodconverter.core.office.RetryTimeoutException;
import org.jodconverter.local.process.LinesPumpStreamHandler;
import org.jodconverter.local.process.ProcessManager;
import org.jodconverter.local.process.ProcessQuery;

/**
 * An OfficeProcess represents an instance of an office program that is executed by JODConverter.
 */
class OfficeProcess {

  // TODO: Make process constants configurable
  private static final long START_PROCESS_RETRY = 500L;
  private static final long START_PROCESS_TIMEOUT = 10_000L;

  private static final Logger LOGGER = LoggerFactory.getLogger(OfficeProcess.class);

  private VerboseProcess process;
  private OfficeDescriptor descriptor;
  private long pid = PID_UNKNOWN;
  private final OfficeUrl officeUrl;
  private final OfficeProcessConfig config;
  private final File instanceProfileDir;

  /**
   * Constructs a new instance of an office process class for the specified URL with default
   * configuration.
   *
   * @param officeUrl The URL for which the process is created.
   */
  public OfficeProcess(final OfficeUrl officeUrl) {
    this(officeUrl, new OfficeProcessConfig());
  }

  /**
   * Constructs a new instance of an office process class with the specified configuration.
   *
   * @param officeUrl The URL for which the process is created.
   * @param config The office process configuration.
   */
  public OfficeProcess(final OfficeUrl officeUrl, final OfficeProcessConfig config) {

    this.officeUrl = officeUrl;
    this.config = config;
    this.instanceProfileDir = getInstanceProfileDir();
  }

  /**
   * Checks if there already is an office process that runs with the connection string we want to
   * use. The process will be killed if the kill switch is on.
   *
   * @param processQuery The query that connection string we want to use.
   * @throws org.jodconverter.core.office.OfficeException If the verification fails.
   */
  private void checkForExistingProcess(final ProcessQuery processQuery) throws OfficeException {

    try {
      // Search for an existing process that would prevent us to start a new
      // office process with the same connection string.
      final ProcessManager processManager = config.getProcessManager();
      long existingPid = processManager.findPid(processQuery);

      // Kill the any running process with the same connection string if the kill switch is on
      if (existingPid > PID_UNKNOWN && config.isKillExistingProcess()) {
        LOGGER.warn(
            "A process with --accept '{}' is already running; pid {}; trying to kill it...",
            processQuery.getArgument(),
            existingPid);
        processManager.kill(null, existingPid);
        waitForProcessToDie();
        existingPid = processManager.findPid(processQuery);
      }

      if (existingPid > PID_UNKNOWN) {
        throw new OfficeException(
            String.format(
                "A process with --accept '%s' is already running; pid %d",
                processQuery.getArgument(), existingPid));
      }

      LOGGER.debug(
          "Checking existing process done successfully. No process running with --accept '{}'",
          processQuery.getArgument());

    } catch (IOException ioEx) {
      throw new OfficeException(
          String.format(
              "Unable to check if there is already an existing process with --accept '%s'",
              processQuery.getArgument()),
          ioEx);
    }
  }

  /** Deletes the profile directory of the office process. */
  public void deleteInstanceProfileDir() {

    LOGGER.debug("Deleting instance profile directory '{}'", instanceProfileDir);
    try {
      FileUtils.deleteDirectory(instanceProfileDir);
    } catch (IOException ioEx) {
      final File oldProfileDir =
          new File(
              instanceProfileDir.getParentFile(),
              instanceProfileDir.getName() + ".old." + System.currentTimeMillis());
      if (instanceProfileDir.renameTo(oldProfileDir)) {
        LOGGER.warn("Could not delete profileDir; renamed it to '" + oldProfileDir + "'", ioEx);
      } else {
        LOGGER.error("Could not delete profileDir", ioEx);
      }
    }
  }

  private void detectOfficeVersion() {

    // Create the command used to launch the office process
    final List<String> command = new ArrayList<>();
    final File executable = LocalOfficeUtils.getOfficeExecutable(config.getOfficeHome());
    if (config.getRunAsArgs() != null) {
      command.addAll(Arrays.asList(config.getRunAsArgs()));
    }

    final String execPath = executable.getAbsolutePath();

    descriptor = OfficeDescriptor.fromExecutablePath(execPath);

    // On windows, we can't try the help option.
    // See https://bugs.documentfoundation.org/show_bug.cgi?id=100826
    if (SystemUtils.IS_OS_WINDOWS) {
      return;
    }

    final String prefix = descriptor.useLongOptionNameGnuStyle() ? "--" : "-";

    command.add(execPath);
    command.add(prefix + "invisible");
    command.add(prefix + "help");
    command.add(prefix + "headless");
    command.add(prefix + "nocrashreport");
    command.add(prefix + "nodefault");
    command.add(prefix + "nofirststartwizard");
    command.add(prefix + "nolockcheck");
    command.add(prefix + "nologo");
    command.add(prefix + "norestore");
    command.add("-env:UserInstallation=" + LocalOfficeUtils.toUrl(instanceProfileDir));
    final ProcessBuilder processBuilder = new ProcessBuilder(command);
    try {
      final Process process = processBuilder.start();
      final LinesPumpStreamHandler handler =
          new LinesPumpStreamHandler(process.getInputStream(), process.getErrorStream());
      handler.start();
      try {
        process.waitFor();
        handler.stop();
      } catch (InterruptedException ignored) {
        // Ignore
      }
      descriptor = OfficeDescriptor.fromHelpOutput(handler.getOutputPumper().getLines());
    } catch (IOException ioEx) {
      LOGGER.warn("An I/O error prevents us to determine office version", ioEx);
    }
  }

  /**
   * Kills the office process instance.
   *
   * @param retryInterval The interval between each exit code retrieval attempt.
   * @param retryTimeout The timeout after which we won't try again to retrieve the exit code.
   * @throws org.jodconverter.core.office.OfficeException If we are unable to kill the process due
   *     to an I/O error occurs.
   * @throws org.jodconverter.core.office.RetryTimeoutException If we are unable to get the exit
   *     code of the process.
   */
  public int forciblyTerminate(final long retryInterval, final long retryTimeout)
      throws OfficeException, RetryTimeoutException {

    // No need to terminate anything if the process has never been started
    if (process == null) {
      return 0; // success
    }

    LOGGER.info(
        "Trying to forcibly terminate process: '{}'; pid: {}",
        officeUrl.getConnectionParametersAsString(),
        pid == PID_NOT_FOUND ? "PID_NOT_FOUND" : pid == PID_UNKNOWN ? "PID_UNKNOWN" : pid);
    try {
      config.getProcessManager().kill(process.getProcess(), pid);
    } catch (IOException ioEx) {
      throw new OfficeException(
          "Unable to kill the process with pid: "
              + (pid == PID_NOT_FOUND ? "PID_NOT_FOUND" : pid == PID_UNKNOWN ? "PID_UNKNOWN" : pid),
          ioEx);
    }

    return getExitCode(retryInterval, retryTimeout);
  }

  /**
   * Gets the exit code of the office process.
   *
   * @return The exit value of the process. The value 0 indicates normal termination. If the process
   *     is not yet terminated, {@code null} is returned.
   */
  public Integer getExitCode() {

    // If the process has never been started, just return a success exit code
    if (process == null) {
      return 0; // success
    }

    return process.getExitCode();
  }

  /**
   * Gets the exit code of the office process. We will try to get the exit code until we succeed or
   * that the specified timeout is reached.
   *
   * @param retryInterval The interval between each exit code retrieval attempt.
   * @param retryTimeout The timeout after which we won't try again to retrieve the exit code.
   * @return The exit value of the process. The value 0 indicates normal termination.
   * @throws org.jodconverter.core.office.OfficeException If we are unable to kill the process.
   * @throws org.jodconverter.core.office.RetryTimeoutException If we are unable to get the exit
   *     code of the process.
   */
  public int getExitCode(final long retryInterval, final long retryTimeout)
      throws OfficeException, RetryTimeoutException {

    // If the process has never been started, just return a success exit code
    if (process == null) {
      return 0; // success
    }

    try {
      final ExitCodeRetryable retryable = new ExitCodeRetryable(process);
      retryable.execute(retryInterval, retryTimeout);
      return retryable.getExitCode();
    } catch (RetryTimeoutException timeoutEx) {
      throw timeoutEx;
    } catch (Exception ex) {
      throw new OfficeException("Could not get the process exit code", ex);
    }
  }

  /**
   * Gets the profile directory of the office process.
   *
   * @return The profile directory instance.
   */
  private File getInstanceProfileDir() {

    return new File(
        config.getWorkingDir(),
        ".jodconverter_"
            + officeUrl.getConnectionAndParametersAsString().replace(',', '_').replace('=', '-'));
  }

  /**
   * Gets whether the office process is running.
   *
   * @return {@code true} is the office process is running; {@code false otherwise}.
   */
  public boolean isRunning() {

    return process != null && getExitCode() == null;
  }

  /**
   * Prepare the profile directory of the office process.
   *
   * @throws org.jodconverter.core.office.OfficeException If the template profile directory cannot
   *     be copied to the new instance profile directory.
   */
  private void prepareInstanceProfileDir() throws OfficeException {

    if (instanceProfileDir.exists()) {
      LOGGER.warn("Profile dir '{}' already exists; deleting", instanceProfileDir);
      deleteInstanceProfileDir();
    }
    if (config.getTemplateProfileDir() != null) {
      try {
        FileUtils.copyDirectory(config.getTemplateProfileDir(), instanceProfileDir);
      } catch (IOException ioEx) {
        throw new OfficeException("Failed to create the instance profile directory", ioEx);
      }
    }
  }

  /**
   * Prepare the ProcessBuilder that will be used to launch the office process.
   *
   * @param acceptString The connection string (accept argument) of the office process.
   * @return The created ProcessBuilder.
   */
  private ProcessBuilder prepareProcessBuilder(final String acceptString) {

    // Create the command used to launch the office process
    final List<String> command = new ArrayList<>();
    final File executable = LocalOfficeUtils.getOfficeExecutable(config.getOfficeHome());
    if (config.getRunAsArgs() != null) {
      command.addAll(Arrays.asList(config.getRunAsArgs()));
    }

    // LibreOffice:
    // https://help.libreoffice.org/Common/Starting_the_Software_With_Parameters
    //
    // Apache OpenOffice:
    // https://wiki.openoffice.org/wiki/Framework/Article/Command_Line_Arguments

    final String execPath = executable.getAbsolutePath();
    final String prefix = descriptor.useLongOptionNameGnuStyle() ? "--" : "-";
    command.add(execPath);
    command.add(prefix + "accept=" + acceptString);
    command.add(prefix + "headless");
    command.add(prefix + "invisible");
    command.add(prefix + "nocrashreport");
    command.add(prefix + "nodefault");
    command.add(prefix + "nofirststartwizard");
    command.add(prefix + "nolockcheck");
    command.add(prefix + "nologo");
    command.add(prefix + "norestore");
    command.add("-env:UserInstallation=" + LocalOfficeUtils.toUrl(instanceProfileDir));

    // It could be interesting to use the LibreOffice pidfile switch
    // to retrieve the LibreOffice pid. But is it reliable ? And it would
    // not work with Apache OpenOffice.

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("ProcessBuilder command: {}", String.join(" ", command));
    }
    return new ProcessBuilder(command);
  }

  /**
   * Starts the office process.
   *
   * @throws org.jodconverter.core.office.OfficeException If the office process cannot be started.
   */
  public void start() throws OfficeException {

    start(false);
  }

  /**
   * Starts the office process.
   *
   * @param restart Indicates whether it is a fresh start or a restart. A restart will assume that
   *     the instance profile directory is already created. To recreate the instance profile
   *     directory, {@code restart} should be set to {@code false}.
   * @throws org.jodconverter.core.office.OfficeException If the office process cannot be started.
   */
  public void start(final boolean restart) throws OfficeException {

    final String acceptString =
        officeUrl.getConnectionAndParametersAsString()
            + ";"
            + officeUrl.getProtocolAndParametersAsString()
            + ";"
            + officeUrl.getRootOid();

    // Search for an existing process.
    final ProcessQuery processQuery = new ProcessQuery("soffice", acceptString);
    checkForExistingProcess(processQuery);

    // Prepare the instance directory only on first start
    if (!restart) {
      prepareInstanceProfileDir();
    }

    // Determiner office version
    detectOfficeVersion();

    // Create the builder used to launch the office process
    final ProcessBuilder processBuilder = prepareProcessBuilder(acceptString);

    // Launch the process.
    LOGGER.info("OFFICE HOME: {}", config.getOfficeHome());
    LOGGER.info(
        "Starting process with --accept '{}' and profileDir '{}'",
        acceptString,
        instanceProfileDir);

    try {
      // Start the process.
      final StartProcessRetryable retryable =
          new StartProcessRetryable(config, processBuilder, processQuery);
      retryable.execute(START_PROCESS_RETRY, START_PROCESS_TIMEOUT);
      process = retryable.getProcess();
      pid = retryable.getProcessId();

      LOGGER.info(
          "Started process; pid: {}",
          pid == PID_NOT_FOUND ? "PID_NOT_FOUND" : pid == PID_UNKNOWN ? "PID_UNKNOWN" : pid);

    } catch (OfficeException officeEx) {
      throw officeEx;
    } catch (Exception ex) {
      throw new OfficeException(
          String.format(
              "An error prevents us to start a process with --accept '%s'",
              processQuery.getArgument()),
          ex);
    }

    if (pid == PID_NOT_FOUND) {
      throw new OfficeException(
          String.format(
              "A process with --accept '%s' started but its pid could not be found", acceptString));
    }
  }

  private void waitForProcessToDie() {

    try {
      Thread.sleep(1000);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}