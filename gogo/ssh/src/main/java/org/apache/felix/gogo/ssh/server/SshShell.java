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
package org.apache.felix.gogo.ssh.server;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Map;

import org.apache.felix.service.command.CommandProcessor;
import org.apache.felix.service.command.CommandSession;
import org.apache.sshd.common.channel.PtyMode;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SessionAware;
import org.apache.sshd.server.Signal;
import org.apache.sshd.server.SignalListener;
import org.apache.sshd.server.session.ServerSession;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.Attributes;
import org.jline.terminal.Attributes.ControlChar;
import org.jline.terminal.Attributes.InputFlag;
import org.jline.terminal.Attributes.LocalFlag;
import org.jline.terminal.Attributes.OutputFlag;

public class SshShell implements Command, SessionAware {
	public static final String VAR_TERMINAL = ".terminal";

	private CommandProcessor processor;

	private InputStream in;

    private OutputStream out;

    private OutputStream err;

    private ExitCallback callback;

    private boolean closed;
    
	SshShell(CommandProcessor processor) {
		this.processor = processor;
	}

	@Override
    public void setInputStream(final InputStream in) {
        this.in = in;
    }

	@Override
    public void setOutputStream(final OutputStream out) {
        this.out = out;
    }

	@Override
    public void setErrorStream(final OutputStream err) {
        this.err = err;
    }

	@Override
    public void setExitCallback(ExitCallback callback) {
        this.callback = callback;
    }

	@Override
    public void setSession(ServerSession session) {
    }

	@Override
    public void start(final Environment env) throws IOException {
        try {
            new Thread() {
                public void run() {
                    try {
                        SshShell.this.run(env);
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
            }.start();
        } catch (Exception e) {
            throw (IOException) new IOException("Unable to start shell").initCause(e);
        }
    }

    public void run(final Environment env) throws Exception {
        try {
            final Terminal terminal = TerminalBuilder.builder()
                    .name("gogo")
                    .type(env.getEnv().get("TERM"))
                    .system(false)
                    .streams(in, out)
                    .build();
            terminal.setSize(getSize(env));
            Attributes attr = terminal.getAttributes();
            adaptControlChars(env, attr);
            terminal.setAttributes(attr);
            PrintStream pout = new PrintStream(terminal.output());
            CommandSession session = processor.createSession(terminal.input(), pout, pout);
            session.put(VAR_TERMINAL, terminal);
            for (Map.Entry<String, String> e : env.getEnv().entrySet()) {
                session.put(e.getKey(), e.getValue());
            }
            env.addSignalListener(new SignalListener() {
				
				public void signal(Signal signal) {
					terminal.setSize(getSize(env));
	                terminal.raise(Terminal.Signal.WINCH);
				}
            }, Signal.WINCH);
            session.execute("gosh --login");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

	private Size getSize(Environment env) {
		return new Size(Integer.parseInt(env.getEnv().get("COLUMNS")),
		        Integer.parseInt(env.getEnv().get("LINES")));
	}

	private void adaptControlChars(Environment env, Attributes attr) {
		for (Map.Entry<PtyMode, Integer> e : env.getPtyModes().entrySet()) {
		    switch (e.getKey()) {
		        case VINTR:
		            attr.setControlChar(ControlChar.VINTR, e.getValue());
		            break;
		        case VQUIT:
		            attr.setControlChar(ControlChar.VQUIT, e.getValue());
		            break;
		        case VERASE:
		            attr.setControlChar(ControlChar.VERASE, e.getValue());
		            break;
		        case VKILL:
		            attr.setControlChar(ControlChar.VKILL, e.getValue());
		            break;
		        case VEOF:
		            attr.setControlChar(ControlChar.VEOF, e.getValue());
		            break;
		        case VEOL:
		            attr.setControlChar(ControlChar.VEOL, e.getValue());
		            break;
		        case VEOL2:
		            attr.setControlChar(ControlChar.VEOL2, e.getValue());
		            break;
		        case VSTART:
		            attr.setControlChar(ControlChar.VSTART, e.getValue());
		            break;
		        case VSTOP:
		            attr.setControlChar(ControlChar.VSTOP, e.getValue());
		            break;
		        case VSUSP:
		            attr.setControlChar(ControlChar.VSUSP, e.getValue());
		            break;
		        case VDSUSP:
		            attr.setControlChar(ControlChar.VDSUSP, e.getValue());
		            break;
		        case VREPRINT:
		            attr.setControlChar(ControlChar.VREPRINT, e.getValue());
		            break;
		        case VWERASE:
		            attr.setControlChar(ControlChar.VWERASE, e.getValue());
		            break;
		        case VLNEXT:
		            attr.setControlChar(ControlChar.VLNEXT, e.getValue());
		            break;
		        /*
		        case VFLUSH:
		            attr.setControlChar(ControlChar.VMIN, e.getValue());
		            break;
		        case VSWTCH:
		            attr.setControlChar(ControlChar.VTIME, e.getValue());
		            break;
		        */
		        case VSTATUS:
		            attr.setControlChar(ControlChar.VSTATUS, e.getValue());
		            break;
		        case VDISCARD:
		            attr.setControlChar(ControlChar.VDISCARD, e.getValue());
		            break;
		        case ECHO:
		            attr.setLocalFlag(LocalFlag.ECHO, e.getValue() != 0);
		            break;
		        case ICANON:
		            attr.setLocalFlag(LocalFlag.ICANON, e.getValue() != 0);
		            break;
		        case ISIG:
		            attr.setLocalFlag(LocalFlag.ISIG, e.getValue() != 0);
		            break;
		        case ICRNL:
		            attr.setInputFlag(InputFlag.ICRNL, e.getValue() != 0);
		            break;
		        case INLCR:
		            attr.setInputFlag(InputFlag.INLCR, e.getValue() != 0);
		            break;
		        case IGNCR:
		            attr.setInputFlag(InputFlag.IGNCR, e.getValue() != 0);
		            break;
		        case OCRNL:
		            attr.setOutputFlag(OutputFlag.OCRNL, e.getValue() != 0);
		            break;
		        case ONLCR:
		            attr.setOutputFlag(OutputFlag.ONLCR, e.getValue() != 0);
		            break;
		        case ONLRET:
		            attr.setOutputFlag(OutputFlag.ONLRET, e.getValue() != 0);
		            break;
		        case OPOST:
		            attr.setOutputFlag(OutputFlag.OPOST, e.getValue() != 0);
		            break;
		        default:
		        	break;
		    }
		}
	}

	@Override
    public void destroy() {
        if (!closed) {
            closed = true;
            flush(out, err);
            close(in, out, err);
            callback.onExit(0);
        }
    }
    
    private static void flush(OutputStream... streams) {
        for (OutputStream s : streams) {
            try {
                s.flush();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    static void close(Closeable... closeables) {
        for (Closeable c : closeables) {
            try {
                c.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

}