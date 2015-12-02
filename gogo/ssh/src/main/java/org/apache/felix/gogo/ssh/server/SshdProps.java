package org.apache.felix.gogo.ssh.server;

public @interface SshdProps {
	int port() default 2022;
	String ip() default "127.0.0.1";
}
