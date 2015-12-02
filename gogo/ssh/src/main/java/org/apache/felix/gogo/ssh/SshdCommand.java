/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.gogo.ssh;

import java.io.IOException;
import java.util.List;

import org.apache.felix.gogo.ssh.server.ServerManager;
import org.apache.felix.service.command.CommandSession;
import org.apache.felix.service.command.Descriptor;
import org.jline.builtins.Options;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

@Component(
		property={
				"osgi.command.scope=gogo",
				"osgi.command.function=sshd"
		})
public class SshdCommand {
	@Reference
    private ServerManager server;

    public SshdCommand() {
    }

    @Descriptor("Manage sshd")
    public void sshd(CommandSession session, String[] argv) throws IOException {
        final String[] usage = {"sshd - start an ssh server",
                "Usage: sshd [-i ip] [-p port] start | stop | status",
                "  -i --ip=INTERFACE        listen interface (default=127.0.0.1)",
                "  -p --port=PORT           listen port (default=2022)",
                "  -? --help                show help"};

        Options opt = Options.compile(usage).parse(argv);
        List<String> args = opt.args();

        if (opt.isSet("help") || args.isEmpty()) {
            opt.usage(System.err);
            return;
        }

        String command = args.get(0);

        if ("start".equals(command)) {
        } else if ("stop".equals(command)) {
            stop();
        } else if ("status".equals(command)) {
            status();
        } else {
            throw opt.usageError("bad command: " + command);
        }

    }

    private void status() {
    	/*
        if (server != null) {
            System.out.println("sshd is running on " + ip + ":" + port);
        } else {
            System.out.println("sshd is not running.");
        }
        */
    }

    private void stop() throws IOException {
        server.stop();
    }
}
