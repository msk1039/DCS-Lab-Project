package examnet;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * The remote interface a centre exports to its students (Lab 3, Java RMI). Every method can fail
 * on the network, so every method declares RemoteException.
 */
public interface ExamService extends Remote {

    /** Register at this centre. Returns a welcome line. */
    String login(String roll) throws RemoteException;

    /** Milliseconds left: -1 before START, 0 after STOP. Uses the centre's synchronised clock. */
    long timeLeftMs() throws RemoteException;

    /** The questions, without answers. Empty before START. */
    String[] paper() throws RemoteException;

    /** Answer question q with option A to D. Returns ACCEPTED or REJECTED with the reason. */
    String submit(String roll, int q, char option) throws RemoteException;

    /** End this student's exam. */
    String finish(String roll) throws RemoteException;
}
