package examnet;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.rmi.server.RMIServerSocketFactory;

/**
 * Makes the RMI registry and the exported ExamService listen on 127.0.0.1 only, so the demo never
 * opens a port to the network. On real exam centres this factory would bind the centre's own address.
 */
public final class LoopbackSockets implements RMIServerSocketFactory, Serializable {

    private static final long serialVersionUID = 1L;

    public ServerSocket createServerSocket(int port) throws IOException {
        return new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LoopbackSockets; // RMI reuses one listener for equal factories
    }

    @Override
    public int hashCode() {
        return 1;
    }
}
