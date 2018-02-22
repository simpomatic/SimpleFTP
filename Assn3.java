import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPConnectionClosedException;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.io.CopyStreamEvent;
import org.apache.commons.net.io.CopyStreamListener;
import org.apache.commons.net.PrintCommandListener;

/**
 * This program connects to an FTP server and executes the given commands on the server in FIFO order.
 * 
 * @author Luis Guillermo Pedroza-Soto
 */
public class Assn3 {
    public static final String help = "Expected Parameters: <hostname> <username:password> [commands]";
    public static final String commands = "The following commands are supported: \n"
            + "ls : list contents of remote directory\n" + "cd 'directory name' : change to the remote directory\n"
            + "cd .. : change remote directory to its parent\n" + "delete 'file name' : delete the remote file\n"
            + "get 'file name' : receive the remote file\n"
            + "get 'directory name' : receive the remote directory recursively\n"
            + "put 'file path' : send the local file to the current working directory\n"
            + "put 'directory path' 'remote path': send the local directory recursively\n"
            + "mkdir 'directory name' : make the directory on remote machine\n"
            + "rmdir 'directory name' : remove the remote directory recursively\n";

    /**
     * Thee main, the pinnacle of all the awesome sauce.
     */
    public static void main(String[] args) {
        final int minParams = 3;

        //Minimum number of parameters were not provided
        if (minParams > args.length) {
            System.err.println(help);
            System.exit(1);
        }

        boolean binaryTransfer = true, error = false, localActive = false, verbose = true;

        int base = 0;
        // The ++ is used after the integer was that the value is read first and then incremented, therefore in this case it will get args[0] then increment base by 1, this will be used several times throughout
        String server = args[base++];
        int port = 21;
        String credentials[] = args[base++].split(":");
        String user = credentials[0];
        String pass = credentials[1];

        final FTPClient ftp = new FTPClient();
        try {
            int reply;
            // Port was specified
            if (port > 0) {
                ftp.connect(server, port);
            }
            // No port was specified
            else {
                ftp.connect(server);
            }

            // After connection attempt, you should check the reply code to verify
            // success.
            reply = ftp.getReplyCode();

            if (!FTPReply.isPositiveCompletion(reply)) {
                ftp.disconnect();
                System.err.println("FTP server refused connection.");
                System.exit(1);
            }

            System.out.println("Connected to " + server + " on " + (port > 0 ? port : ftp.getDefaultPort()));
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
            // Check if the user was successfully logged in
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

                // Progress bar for downloads
                if (verbose) {
                    ftp.setCopyStreamListener(createListener());
                }

                // cmd is instantiated outside of the try-catch so it can still be referenced in the catch block to alert the user of it's misuse
                String cmd = "";

                // This is wrapped inside a try-catch statement to prevent index out of bound issues, in the event the user did not supply the correct amount of parameters
                try {
                    while (base < args.length) {
                        cmd = args[base++];
                        // LS command: list directory
                        if (cmd.equalsIgnoreCase("ls")) {
                            listDirectory(ftp);
                        }
                        // CD command: change directory
                        else if (cmd.equalsIgnoreCase("cd")) {
                            String dir = args[base++];
                            boolean success = changeWorkingDirectory(ftp, dir);

                            if (success) {
                                System.out.println("Successful change of working directory!");
                            } else {
                                System.err.println("Unable to change the working directory!");
                            }
                        }
                        // DELETE command
                        else if (cmd.equalsIgnoreCase("delete")) {
                            String name = args[base++];
                            boolean success = ftp.deleteFile(name);

                            if (success) {
                                System.out.println("File was successfully removed!");
                            } else {
                                System.err.println("File was not removed!");
                            }
                        }
                        // GET command: automatically detects if a file or directory is desired
                        else if (cmd.equalsIgnoreCase("get")) {
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
                                    boolean success = downloadSingleFile(ftp, remoteFilePath, filePath);

                                    if (success) {
                                        System.out.println("File has been downloaded successfully.");
                                    } else {
                                        System.err.println("File was not downloaded.");
                                    }
                                }
                            }
                            // Multiple files were found under that path i.e. this is a directory
                            else {
                                downloadDirectory(ftp, remoteFilePath, "", filePath);
                            }
                        }
                        // STORE command: store a file or directory in the FTP server
                        else if (cmd.equalsIgnoreCase("put")) {
                            String localPath = args[base++];
                            Path path = Paths.get(localPath);
                            // A directory is found at the path
                            if (Files.isDirectory(path)) {
                                String remotePath = args[base++];
                                uploadDirectory(ftp, remotePath, localPath, "");
                            }
                            // A single file was found at the given path
                            else {
                                String fileName = path.getFileName().toString();
                                boolean success = uploadSingleFile(ftp, fileName, localPath);

                                if (success) {
                                    System.out.println("File was successfully uploaded!");
                                } else {
                                    System.err.println("File was not uploaded.");
                                }
                            }
                        }
                        // MKDIR command: Make a directory at the current working directory on the FTP server
                        else if (cmd.equalsIgnoreCase("mkdir")) {
                            String remotePath = args[base++];

                            boolean success = ftp.makeDirectory(remotePath);

                            if (success) {
                                System.out.println("Directory was successfully created!");
                            } else {
                                System.err.println("Directory was not created.");
                            }
                        }
                        // RMDIR command: Remove the specified directory and all files and sub-directories found within
                        else if (cmd.equalsIgnoreCase("rmdir")) {
                            String remotePath = args[base++];

                            removeDirectory(ftp, remotePath, "");
                        }
                        // They did not supply a valid command, show the list of valid commands
                        else {
                            System.err.println(commands);
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

    /**
     * Change the working directory of the FTP server.
     * 
     * @param ftpClient
     *          An instance of org.apache.commons.net.ftp.FTPClient class.
     * @param remoteDirPath
     *          Path of the directory on the remote server.
     * @return  true if the working directory was successsfully changed, false if not
     * @throws IOException
     *          if any network or IO error occurred.
     * @author Luis Guillermo Pedroza-Soto
     */
    private static boolean changeWorkingDirectory(FTPClient ftpClient, String remoteDirPath) throws IOException {
        boolean success;

        //Parent directory is desired
        if (remoteDirPath.equals("..")) {
            success = ftpClient.changeToParentDirectory();
        } else {
            success = ftpClient.changeWorkingDirectory(remoteDirPath);
        }

        return success;
    }

    /**
     * Tracks the progress of a download from the FTP server.
     * 
     * @return  An instance of org.apache.commons.net.io.CopyStreamListener class.
     * @author  commons.apache.org
     */
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

    /**
     * Retrieves the contents of a directory including any sub directories recursively.
     * 
     * @param ftpClient
     *          An instance of org.apache.commons.net.ftp.FTPClient class.
     * @param remoteDirPath
     *          Path of the destination directory on the server.
     * @param localParentDir
     *          Path of the local directory being uploaded.
     * @param remoteParentDir
     *          Path of the parent directory of the current directory on the
     *          server (used by recursive calls).
     * @throws IOException
     *          if any network or IO error occurred.
     * @author www.codejava.net
     */
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
                        System.err.println("COULD NOT create the directory: " + newDirPath);
                    }

                    // download the sub directory
                    downloadDirectory(ftpClient, dirToList, currentFileName, saveDir);
                } else {
                    // download the file
                    boolean success = downloadSingleFile(ftpClient, filePath, newDirPath);
                    if (success) {
                        System.out.println("DOWNLOADED the file: " + filePath);
                    } else {
                        System.err.println("COULD NOT download the file: " + filePath);
                    }
                }
            }
        }
    }

    /**
     * Download a single file from the FTP server
     * @param ftpClient 
     *          an instance of org.apache.commons.net.ftp.FTPClient class.
     * @param remoteFilePath 
     *          path of the file on the server
     * @param savePath 
     *          path of directory where the file will be stored
     * @return  true if the file was downloaded successfully, false otherwise
     * @throws IOException 
     *          if any network or IO error occurred.
     * @author www.codejava.net
     */
    private static boolean downloadSingleFile(FTPClient ftpClient, String remoteFilePath, String savePath)
            throws IOException {
        File downloadFile = new File(savePath);

        File parentDir = downloadFile.getParentFile();
        if (!parentDir.exists()) {
            parentDir.mkdir();
        }

        OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(downloadFile));
        try {
            return ftpClient.retrieveFile(remoteFilePath, outputStream);
        } finally {
            if (outputStream != null) {
                outputStream.close();
            }
        }
    }

    /**
     * List the contents of the current working directory on the FTP server.
     * 
     * @param ftpClient
     *          An instance of org.apache.commons.net.ftp.FTPClient class.
     * @throws IOException
     *          if any network or IO error occurred.
     * @author Luis Guillermo Pedroza-Soto
     */
    private static void listDirectory(FTPClient ftpClient) throws IOException {
        for (String s : ftpClient.listNames()) {
            System.out.println(s);
        }
    }

    /**
     * Removes a non-empty directory by delete all its sub files and
     * sub directories recursively. And finally remove the directory.
     * 
     * @param ftpClient
     *          An instance of org.apache.commons.net.ftp.FTPClient class.
     * @param parentDir
     *          Path of the parent directory.
     * @param currentDir
     *          Path of the current directory.
     * @throws IOException
     *          if any network or IO error occurred.
     * @author www.codejava.net
     */
    private static void removeDirectory(FTPClient ftpClient, String parentDir, String currentDir) throws IOException {
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

                if (aFile.isDirectory()) {
                    // remove the sub directory
                    removeDirectory(ftpClient, dirToList, currentFileName);
                } else {
                    // delete the file
                    boolean deleted = ftpClient.deleteFile(filePath);
                    if (deleted) {
                        System.out.println("DELETED the file: " + filePath);
                    } else {
                        System.err.println("CANNOT delete the file: " + filePath);
                    }
                }
            }

            // finally, remove the directory itself
            boolean removed = ftpClient.removeDirectory(dirToList);
            if (removed) {
                System.out.println("REMOVED the directory: " + dirToList);
            } else {
                System.err.println("CANNOT remove the directory: " + dirToList);
            }
        }
    }

    /**
     * Upload a whole directory (including its nested sub directories and files)
     * to a FTP server.
     *
     * @param ftpClient
     *            an instance of org.apache.commons.net.ftp.FTPClient class.
     * @param remoteDirPath
     *            Path of the destination directory on the server.
     * @param localParentDir
     *            Path of the local directory being uploaded.
     * @param remoteParentDir
     *            Path of the parent directory of the current directory on the
     *            server (used by recursive calls).
     * @throws IOException
     *             if any network or IO error occurred.
     * @author www.codejava.net
     */
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
                    boolean uploaded = uploadSingleFile(ftpClient, remoteFilePath, localFilePath);
                    if (uploaded) {
                        System.out.println("UPLOADED a file to: " + remoteFilePath);
                    } else {
                        System.err.println("COULD NOT upload the file: " + localFilePath);
                    }
                } else {
                    // create directory on the server
                    boolean created = ftpClient.makeDirectory(remoteFilePath);
                    if (created) {
                        System.out.println("CREATED the directory: " + remoteFilePath);
                    } else {
                        System.err.println("COULD NOT create the directory: " + remoteFilePath);
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

    /**
     * Upload a single file to the FTP server.
     *
     * @param ftpClient
     *            an instance of org.apache.commons.net.ftp.FTPClient class.
     * @param localFilePath
     *            Path of the file on local computer
     * @param remoteFilePath
     *            Path of the file on remote the server
     * @return true if the file was uploaded successfully, false otherwise
     * @throws IOException
     *             if any network or IO error occurred.
     * @author Luis Guillermo Pedroza-Soto
     */
    private static boolean uploadSingleFile(FTPClient ftpClient, String remoteFilePath, String localFilePath)
            throws IOException {
        File localFile = new File(localFilePath);
        InputStream inputStream = new FileInputStream(localFile);

        try {
            return ftpClient.storeFile(remoteFilePath, inputStream);
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }
}