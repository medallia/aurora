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

public class V009_AddKillPolicyToTaskConfig implements MigrationScript {
    @Override
    public BigDecimal getId() {
        return BigDecimal.valueOf(9L);
    }

    @Override
    public String getDescription() {
        return "Add the grace period for the Mesos kill policy.";
    }

    @Override
    public String getUpScript() {
        return "ALTER TABLE task_configs ADD IF NOT EXISTS kill_policy_grace_period BIGINT;";
    }

    @Override
    public String getDownScript() {
        return "ALTER TABLE task_configs DROP COLUMN IF NOT EXISTS kill_policy_grace_period;";
    }
}
