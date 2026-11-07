/*
 * Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
 *
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
package io.github.vaquarkhan.aegis.validation.frontdoor;

/**
 * The four callers every adopter scenario uses. The user names and passwords are the stock Apache Knox
 * demo LDAP accounts ({@code conf/users.ldif}), so the same people exist behind both front doors.
 * Groups are not fixed here: each scenario maps a persona to the groups its adopter configuration uses.
 */
public enum Persona {
    /** May read; must be refused any write. */
    READER("guest"),
    /** May request writes; needs an independent approval for each one. */
    OPERATOR("sam"),
    /** Approves other people's requests; never executes tools in the scenario. */
    APPROVER("tom"),
    /** Authenticated, but in no adopter group, and outside the Knox route ACL. */
    OUTSIDER("admin");

    private final String user;

    Persona(String user) {
        this.user = user;
    }

    public String user() {
        return user;
    }

    /** Stock Knox demo LDAP derives its local test password from the persona name. */
    String password() {
        return user + "-" + "password";
    }

    /** Own-host static bearer token (at least 16 characters, as the static identity requires). */
    String ownHostToken() {
        return "aegis-validation-token-" + user;
    }

    String tokenEnv() {
        return "AEGIS_VALIDATION_TOKEN_" + name();
    }
}
