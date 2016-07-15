/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.storage.db.migration;

import org.apache.ibatis.migration.MigrationScript;

import java.math.BigDecimal;

public class V003_AddCommandColumnToDockerContainerTable implements MigrationScript {
  @Override
  public BigDecimal getId() {
    return BigDecimal.valueOf(3L);
  }

  @Override
  public String getDescription() {
    return "Add the command column to task_config_docker_containers table.";
  }

  @Override
  public String getUpScript() {
    return "ALTER TABLE task_config_docker_containers ADD command VARCHAR;";
  }

  @Override
  public String getDownScript() {
      return "ALTER TABLE task_config_docker_containers DROP COLUMN command;";
  }
}
