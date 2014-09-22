/*
 * Copyright © 2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.tigon.cli;

/**
 * Commands used in Command Line Interface.
 */
public enum CLICommands {

  START (2, "Starts a Flow", "<Path-to-Jar> <FlowClassName>"),
  LIST (0, "Lists all Flows which are currently running", ""),
  STOP (1, "Stops a Flow", "<FlowName>"),
  DELETE (1, "Stops and Deletes the Queues for a Flow", "<FlowName>"),
  SERVICEINFO (1, "Prints all Services announced in a Flow", "<FlowName>"),
  DISCOVER (2, "Discovers a service endpoint for a Flow", "<FlowName> <ServiceName>"),
  SET (3, "Set Flowlet instances for a Flow", "<FlowName> <FlowletName> <Instances>"),
  FLOWLETINFO (1, "Prints Flowlet Names and corresponding Instances", "<FlowName>"),
  SHOWLOGS (1, "Shows live logs for the Flow", "<FlowName>"),
  QUIT (0, "Quit Tigon Client", "");

  private int argCount;
  private String description;
  private String args;

  CLICommands(int argCount, String description, String args) {
    this.argCount = argCount;
    this.description = description;
    this.args = args;
  }

  public int getArgCount() {
    //Including the command.
    return argCount + 1;
  }

  public String printHelp() {
    return String.format("Command Description : %s\nUsage : %s %s\n", description, this.toString(), args);
  }
}