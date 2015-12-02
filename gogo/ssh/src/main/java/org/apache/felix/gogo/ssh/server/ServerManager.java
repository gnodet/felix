package org.apache.felix.gogo.ssh.server;

import java.io.IOException;
import java.util.Collections;

import org.apache.felix.service.command.CommandProcessor;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.ServerBuilder;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.ScpCommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.subsystem.sftp.SftpSubsystemFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

@Component(configurationPid="org.apache.felix.gogo.sshd")
public class ServerManager {
	private org.apache.sshd.server.SshServer server;

	@Reference
	CommandProcessor processor;

	@Activate
	public void start(SshdProps props) throws IOException {
		server = ServerBuilder.builder().build();
        server.setPort(props.port());
        server.setHost(props.ip());
        server.setShellFactory(new ShellFactoryImpl(processor));
        server.setCommandFactory(new ScpCommandFactory.Builder().withDelegate(new ShellCommandFactory(processor)).build());
        server.setSubsystemFactories(Collections.<NamedFactory<Command>>singletonList(
                new SftpSubsystemFactory.Builder().build()
        ));
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        server.start();
	}
	
	SshServer getServer() {
		return server;
	}
	
	@Deactivate
	public void stop() throws IOException {
		server.close();
	}
}
