import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.apache.commons.net.PrintCommandListener;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPConnectionClosedException;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.io.CopyStreamEvent;
import org.apache.commons.net.io.CopyStreamListener;

public class Assn3 {
    public static final String help = "Expected Parameters: <hostname> <username:password> [commands]";

    public static void main(String[] args) {
        int minParams = 3;

        //Minimum number of parameters were not provided
        if (minParams > args.length) {
            System.err.println(help);
            System.exit(1);
        }

        boolean binaryTransfer = true, error = false, localActive = false;

        int base = 0;
        String server = args[base++];
        int port = 21;
        String credentials[] = args[base++].split(":");
        String user = credentials[0];
        String pass = credentials[1];

        final FTPClient ftp = new FTPClient();
        try {
            int reply;
            if (port > 0) {
                ftp.connect(server, port);
            } else {
                ftp.connect(server);
            }
            System.out.println("Connected to " + server + " on " + (port > 0 ? port : ftp.getDefaultPort()));

            // After connection attempt, you should check the reply code to verify
            // success.
            reply = ftp.getReplyCode();

            if (!FTPReply.isPositiveCompletion(reply)) {
                ftp.disconnect();
                System.err.println("FTP server refused connection.");
                System.exit(1);
            }
        } catch (IOException e) {
            if (ftp.isConnected()) {
                try {
                    ftp.disconnect();
                } catch (IOException f) {
                    // do nothing
                }
            }
            System.err.println("Could not connect to server.");
            e.printStackTrace();
            System.exit(1);
        }

        try {
            if (!ftp.login(user, pass)) {
                ftp.logout();
                error = true;
            } else {
                System.out.println("Remote system is " + ftp.getSystemType());

                if (binaryTransfer) {
                    ftp.setFileType(FTP.BINARY_FILE_TYPE);
                } else {
                    // in theory this should not be necessary as servers should default to ASCII
                    // but they don't all do so - see NET-500
                    ftp.setFileType(FTP.ASCII_FILE_TYPE);
                }

                // Use passive mode as default because most of us are
                // behind firewalls these days.
                if (localActive) {
                    ftp.enterLocalActiveMode();
                } else {
                    ftp.enterLocalPassiveMode();
                }

                while (base < args.length) {
                    String cmd = args[base++];
                    if (cmd.equals("ls")) {
                        for (String s : ftp.listNames()) {
                            System.out.println(s);
                        }
                    } else if (cmd.equals("cd")) {
                        String dir = args[base++];
                        if (dir.equals("..")) {
                            ftp.changeToParentDirectory();
                        } else {
                            ftp.changeWorkingDirectory(dir);
                        }
                    } else if (cmd.equals("delete")) {
                        String name = args[base++];
                        ftp.deleteFile(name);
                    } else if (cmd.equals("get")) {
                        String remoteFile = args[base++];
                        String[] remoteFileName = ftp.listNames(remoteFile);
                        Path currentRelativePath = Paths.get("");
                        String filePath = currentRelativePath.toAbsolutePath().toString();
                        filePath += "/" + ftp.listNames(remoteFileName[0]);

                        File downloadFile = new File(filePath);
                        OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(downloadFile));

                        boolean success = ftp.retrieveFile(remoteFile, outputStream);
                        outputStream.close();

                        if (success) {
                            System.out.println("File has been downloaded successfully.");
                        }
                    }
                }

                ftp.logout();
            }
        } catch (FTPConnectionClosedException e) {
            error = true;
            System.err.println("Server closed connection.");
            e.printStackTrace();
        } catch (IOException e) {
            error = true;
            e.printStackTrace();
        } finally {
            if (ftp.isConnected()) {
                try {
                    ftp.disconnect();
                } catch (IOException f) {
                    // No idea how you got here.
                }
            }
        }
        System.exit(error ? 1 : 0);
    }

    private static CopyStreamListener createListener() {
        return new CopyStreamListener() {
            private long megsTotal = 0;

            @Override
            public void bytesTransferred(CopyStreamEvent event) {
                bytesTransferred(event.getTotalBytesTransferred(), event.getBytesTransferred(), event.getStreamSize());
            }

            @Override
            public void bytesTransferred(long totalBytesTransferred, int bytesTransferred, long streamSize) {
                long megs = totalBytesTransferred / 1000000;
                for (long l = megsTotal; l < megs; l++) {
                    System.err.print("#");
                }
                megsTotal = megs;
            }
        };
    }
}