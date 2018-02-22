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
import java.nio.file.Files;
import java.nio.file.LinkOption;
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

        boolean binaryTransfer = true, error = false, localActive = false, verbose = true;

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

                //Progress bar for downloads
                if (verbose) {
                    ftp.setCopyStreamListener(createListener());
                }

                String cmd = "";
                try {
                    while (base < args.length) {
                        cmd = args[base++];
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
                            boolean success = ftp.deleteFile(name);

                            if (success) {
                                System.out.println("File was successfully removed!");
                            } else {
                                System.err.println("File was not removed!");
                            }
                        } else if (cmd.equals("get")) {
                            String remoteFilePath = args[base++];
                            Path currentRelativePath = Paths.get("");
                            String filePath = currentRelativePath.toAbsolutePath().toString();

                            FTPFile files[] = ftp.listFiles(remoteFilePath);
                            //No files were found under that path
                            if (files.length == 0) {
                                System.err.println("Could not locate specified directory or file.");
                            }
                            // One file was found under that path, could be that only one directory exists inside that path or simply a single file exists in that path
                            else if (files.length == 1) {
                                FTPFile f = files[0];
                                if (f.isDirectory()) {
                                    downloadDirectory(ftp, remoteFilePath, "", filePath);
                                } else if (f.isFile()) {
                                    filePath += "/" + f.getName();

                                    File downloadFile = new File(filePath);
                                    OutputStream outputStream = new BufferedOutputStream(
                                            new FileOutputStream(downloadFile));

                                    boolean success = ftp.retrieveFile(remoteFilePath, outputStream);
                                    outputStream.close();

                                    if (success) {
                                        System.out.println("File has been downloaded successfully.");
                                    } else {
                                        System.out.println("File was not downloaded.");
                                    }
                                }
                            }
                            // Multiple files were found under that path i.e. this is a directory
                            else {
                                downloadDirectory(ftp, remoteFilePath, "", filePath);
                            }
                        } else if (cmd.equals("put")) {
                            String varPath = args[base++];
                            String remotePath = args[base++];
                            Path path = Paths.get(varPath);
                            if (Files.isDirectory(path)) {
                                uploadDirectory(ftp, remotePath, varPath, "");
                            } else {
                                File uploadFile = new File(varPath);
                                InputStream fileInputStream = new FileInputStream(uploadFile);

                                boolean success = ftp.storeFile(remotePath, fileInputStream);
                                fileInputStream.close();

                                if (success) {
                                    System.out.println("File was successfully uploaded!");
                                } else {
                                    System.out.println("File was not uploaded.");
                                }
                            }
                        }
                    }
                } catch (IndexOutOfBoundsException e) {
                    System.err.println("Command " + cmd + " needs an additional parameter(s)!");
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

    private static void downloadDirectory(FTPClient ftpClient, String parentDir, String currentDir, String saveDir)
            throws IOException {
        String dirToList = parentDir;
        if (!currentDir.equals("")) {
            dirToList += "/" + currentDir;
        }

        FTPFile[] subFiles = ftpClient.listFiles(dirToList);

        if (subFiles != null && subFiles.length > 0) {
            for (FTPFile aFile : subFiles) {
                String currentFileName = aFile.getName();
                if (currentFileName.equals(".") || currentFileName.equals("..")) {
                    // skip parent directory and the directory itself
                    continue;
                }
                String filePath = parentDir + "/" + currentDir + "/" + currentFileName;
                if (currentDir.equals("")) {
                    filePath = parentDir + "/" + currentFileName;
                }

                String newDirPath = saveDir + File.separator + parentDir + File.separator + currentDir + File.separator
                        + currentFileName;
                if (currentDir.equals("")) {
                    newDirPath = saveDir + File.separator + parentDir + File.separator + currentFileName;
                }

                if (aFile.isDirectory()) {
                    // create the directory in saveDir
                    File newDir = new File(newDirPath);
                    boolean created = newDir.mkdirs();
                    if (created) {
                        System.out.println("CREATED the directory: " + newDirPath);
                    } else {
                        System.out.println("COULD NOT create the directory: " + newDirPath);
                    }

                    // download the sub directory
                    downloadDirectory(ftpClient, dirToList, currentFileName, saveDir);
                } else {
                    // download the file
                    boolean success = downloadSingleFile(ftpClient, filePath, newDirPath);
                    if (success) {
                        System.out.println("DOWNLOADED the file: " + filePath);
                    } else {
                        System.out.println("COULD NOT download the file: " + filePath);
                    }
                }
            }
        }
    }

    private static boolean downloadSingleFile(FTPClient ftpClient, String remoteFilePath, String savePath)
            throws IOException {
        File downloadFile = new File(savePath);

        File parentDir = downloadFile.getParentFile();
        if (!parentDir.exists()) {
            parentDir.mkdir();
        }

        OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(downloadFile));
        try {
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            return ftpClient.retrieveFile(remoteFilePath, outputStream);
        } catch (IOException ex) {
            throw ex;
        } finally {
            if (outputStream != null) {
                outputStream.close();
            }
        }
    }

    private static void uploadDirectory(FTPClient ftpClient, String remoteDirPath, String localParentDir,
            String remoteParentDir) throws IOException {

        System.out.println("LISTING directory: " + localParentDir);

        File localDir = new File(localParentDir);
        File[] subFiles = localDir.listFiles();
        if (subFiles != null && subFiles.length > 0) {
            for (File item : subFiles) {
                String remoteFilePath = remoteDirPath + "/" + remoteParentDir + "/" + item.getName();
                if (remoteParentDir.equals("")) {
                    remoteFilePath = remoteDirPath + "/" + item.getName();
                }

                if (item.isFile()) {
                    // upload the file
                    String localFilePath = item.getAbsolutePath();
                    System.out.println("About to upload the file: " + localFilePath);
                    boolean uploaded = uploadSingleFile(ftpClient, localFilePath, remoteFilePath);
                    if (uploaded) {
                        System.out.println("UPLOADED a file to: " + remoteFilePath);
                    } else {
                        System.out.println("COULD NOT upload the file: " + localFilePath);
                    }
                } else {
                    // create directory on the server
                    boolean created = ftpClient.makeDirectory(remoteFilePath);
                    if (created) {
                        System.out.println("CREATED the directory: " + remoteFilePath);
                    } else {
                        System.out.println("COULD NOT create the directory: " + remoteFilePath);
                    }

                    // upload the sub directory
                    String parent = remoteParentDir + "/" + item.getName();
                    if (remoteParentDir.equals("")) {
                        parent = item.getName();
                    }

                    localParentDir = item.getAbsolutePath();
                    uploadDirectory(ftpClient, remoteDirPath, localParentDir, parent);
                }
            }
        }
    }

    private static boolean uploadSingleFile(FTPClient ftpClient, String localFilePath, String remoteFilePath)
            throws IOException {
        File localFile = new File(localFilePath);

        InputStream inputStream = new FileInputStream(localFile);
        try {
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            return ftpClient.storeFile(remoteFilePath, inputStream);
        } finally {
            inputStream.close();
        }
    }
}