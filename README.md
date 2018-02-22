# Simple FTP Client

## Prerequisites
### Java virtual machine
You need some sort of Java virtual machine (JVM) installed on your computer. The PATH needs to be set so that you can use the 'java' command on the shell.

### Java development kit
A version of the Java development kit (JDK) will need to be present, preferrably the most up-to-date. The PATH may also need to be set to allow the 'javac' command to be recognized on the shell.

## Getting started
Using your choice of command line shell, navigate to the source where this package is located and run the following commands:

``` bash
javac -cp "commons-net-3.6.jar" Assn3.java
java -cp ".;commons-net-3.6.jar" Assn3 <server ip> <user:pass> [commands]
```

## How to use the client
1. args[0]: IP of a FTP server
2. args[1]: id:password
3. The others are requested jobs. ex) "ls" "mkdir test folder"...
4. The program shall execute the jobs in the given order.

## Supported commands
The follow commands are supported in this FTP client:
  - ls : list contents of remote directory
  - cd .. : change remote directory to its parent
  - cd "directory name" : change to the remote directory provided
  - delete "file name" : delete the remote file
  - get "file name" : receive the remote file
  - get "directory name" : recieve the remote directory recursively
  - put "file name" : send the local file
  - put "directory name" "remote path": sned the local directory recursively
  - mkdir "directory name" : make the directory on remote machine
  - rmdir "directory name" : remove the remote directory recursively
