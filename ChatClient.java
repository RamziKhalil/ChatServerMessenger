import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

public class ChatClient {
	public static final int BLOCK_SIZE = 4096;

	public static final String namePrompt = "What is your name?";
	public static final String messagePrompt = "Enter your message: ";
	public static final String filePrompt = "Which file do you want? ";
	public static final String fileOwnerPrompt = "Who owns the file? ";

	private final String serverAddress;
	private final int serverPort, listeningPort;
	private final boolean _debug;

	private String clientName;
	private String options = "relay";

	private volatile boolean keepRunning = true;

	public ChatClient(String serverAddress, int serverPort, int listeningPort) {
		this.serverAddress = serverAddress;
		this.serverPort = serverPort;
		this.listeningPort = listeningPort;

		_debug = true;
	}

	public void start() {
		// in case we want to switch this to be a thread
		run();
	}

	public void run() {
		// step one - setup a file request listener on the client Socket
		FileRequestListener fileRequestListener = new FileRequestListener(listeningPort);
		fileRequestListener.start();

		// step two - client socket to send and receive messages from the server
		try (Socket clientSocket = new Socket(serverAddress, serverPort)) {

			try (BufferedReader inputReader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
					PrintWriter outputWriter = new PrintWriter(clientSocket.getOutputStream());) {

				// step three create a message handler thread for receiving messages from the
				// server
				MessageHandler msgHandler = new MessageHandler(inputReader);
				msgHandler.start();

				// step four read messages from stdin
				uiMainLoop(new BufferedReader(new InputStreamReader(System.in)), outputWriter);

				msgHandler.join();

				System.out.println("Closing your sockets... goodbye");
				// start the shutdown process
				keepRunning = false;

			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void uiMainLoop(BufferedReader inputReader, PrintWriter outputWriter) {

		try {
			String inputLine;
			// get the client name
			System.out.println(namePrompt);
			this.clientName = inputReader.readLine();
			// send the client intro message
			outputWriter.println("i:" + clientName + ":localhost" + ":" + this.listeningPort);
			outputWriter.flush();
			printMenu();
			while (keepRunning && (inputLine = inputReader.readLine()) != null) {
				if ("m".equals(inputLine)) {
					// next line should be the message
					System.out.println(messagePrompt);
					String message = inputReader.readLine();
					// send the message request to the server
					outputWriter.println("m:" + this.clientName + ":" + message);
				} else if ("f".equals(inputLine)) {
					// next line should be the file owner
					System.out.println(fileOwnerPrompt);
					String fileOwner = inputReader.readLine();
					// next line should be the file name
					System.out.println(filePrompt);
					String fileName = inputReader.readLine();
					// send the file request to server
					outputWriter
							.println("f:" + this.clientName + ":" + this.options + ":" + fileOwner + ":" + fileName);
				} else if ("x".equals(inputLine)) {
					// should be sending a quit command
					outputWriter.println("x:" + this.clientName);
					// returning from this loop
					outputWriter.flush();
					break;
				}
				outputWriter.flush();
				printMenu();
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void printMenu() {
		System.out.println("Enter an option ('m', 'f', 'x'):");
		System.out.println("\t(M)essage (send)");
		System.out.println("\t(F)ile (request)");
		System.out.println("       e(X)it");
	}

	public class MessageHandler extends Thread {
		private final BufferedReader inputReader;

		public MessageHandler(BufferedReader inputReader) {
			this.inputReader = inputReader;

		}

		// Test Message Handler
		@Override
		public void run() {
			try {
				String inputLine;
				while (keepRunning && (inputLine = inputReader.readLine()) != null) {
					if (inputLine.startsWith("m:")) {
						// an incoming message of the form "m:<sender>:<message>"
						String tokens[] = inputLine.split(":");
						System.out.println(tokens[1] + ": " + tokens[2]);
					} else if (inputLine.startsWith("x:")) {
						// server request you shutdown message "x: clientName"
						// break out of this loop, and return
						break;
					} else if (inputLine.startsWith("f:")) {
						// Server send a file "location message"
						// "f:clientName:fileName:peerAddr:peerSocket"
						// create pToPFileHandler thread object
						// start this thread to read the file
					} else {
						// unknown server message
					}
				}
			} catch (IOException e) {
				System.err.println("ChatClient.MessageHandler.run(): IOException: " + e);
				e.printStackTrace();
			}
		}
	}

	public class FileRequestListener extends Thread {

		private final int listeningPort;
		private final List<FileRequestHandler> handlers;

		// the server socket that listens for incoming file requests
		private Socket listeningSocket;

		FileRequestListener(int listeningPort) {
			this.listeningPort = listeningPort;
			this.handlers = new ArrayList<>();
		}

		@Override
		public void run() {
			// create a new server socket
			try (ServerSocket listeningSocket = new ServerSocket(listeningPort)) {

				// Set a timeout for blocking operations
				listeningSocket.setSoTimeout(10000);
				while (keepRunning && !listeningSocket.isClosed()) {
					// Wait for incoming connections
					try {
						Socket newConnection = listeningSocket.accept();

						// start a new thread to read this file
						FileRequestHandler handler = new FileRequestHandler(newConnection);
						handler.start();
					} catch (SocketTimeoutException e) {
					}
				}
			} catch (IOException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
		}
	}

	public class FileRequestHandler extends Thread {
		Socket fileSocket;

		public FileRequestHandler(Socket fileSocket) {
			this.fileSocket = fileSocket;
		}

		@Override
		public void run() {
			if (this.fileSocket != null && !this.fileSocket.isClosed()) {
				try (DataInputStream inStream = new DataInputStream(fileSocket.getInputStream());
						DataOutputStream outStream = new DataOutputStream(fileSocket.getOutputStream())) {
					// get the request message transmit - "t:<filePath>", read - "r:<filePath>"
					String request = inStream.readUTF();
					char op = request.charAt(0);
					String filePath = request.substring(2).trim();
					// branch based on the op type
					if (op == 'r') {
						// reading operation - reading a file from the socket
						receiveFile(inStream, filePath);
					} else if (op == 't') {
						// transmitting operation - transmitting the file over the socket
						transmitFile(outStream, filePath);
					}

					// finally close the socket
					fileSocket.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}

		private void transmitFile(DataOutputStream outStream, String filePath) throws IOException {

			File dir = new File(".");
			String dirName = dir.getAbsolutePath();
			File[] files = dir.listFiles();

			File file = new File(filePath);
			if (true) {
				try (FileInputStream fileStream = new FileInputStream(file)) {

					// first transmit the length
					long length = file.length();
					outStream.writeUTF("l:" + ChatClient.this.clientName + ":" + filePath + ":" + length);

					// transmit the contents in blocks of ChatClient.BLOCK_SIZE
					byte buffer[] = new byte[ChatClient.BLOCK_SIZE];
					int nread;
					// main file reading loop
					while ((nread = fileStream.read(buffer)) != -1) {
						// output file block to the socket
						outStream.write(buffer, 0, nread);
					}
				} catch (FileNotFoundException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} else {
				outStream.writeUTF("n:" + ChatClient.this.clientName + ":" + filePath);
			}
			// finally flush the output stream
			outStream.flush();
		}

		// FIXME
		private void receiveFile(DataInputStream inputStream, String filePath) throws IOException {

			// first get the header line
			String headerLine = inputStream.readUTF();
			String headerTokens[] = headerLine.split(":");
			if ("n".equals(headerTokens[0])) {
				if (_debug)
					System.err.println("Remote File not found. Try again");
			} else if ("l".equals(headerTokens[0])) {
				// make sure the sender field of this header, does not match this.clientName
				if (!ChatClient.this.clientName.equals(headerTokens[1])) {
					// the sender is not this client so continue
					int length = Integer.parseInt(headerTokens[3]);

					File outFile = new File(headerTokens[2]);

					if (outFile.isDirectory()) {
						// if it is a directory not a file??
						return;
					} else if (outFile.exists()) {
						// file exists so delete file
						outFile.delete();
					}

					try (FileOutputStream fileStream = new FileOutputStream(outFile, false)) {
						// read this file from the socket in blocks until we have the whole file
						byte buffer[] = new byte[ChatClient.BLOCK_SIZE];
						int nread, totalBytes = 0;
						while ((nread = inputStream.read(buffer)) != -1) {
							// write this block to our file
							fileStream.write(buffer, 0, nread);
							totalBytes += nread;
						}
						fileStream.flush();

						// check that the file was successfully written,
						// ( total bytes read must be the file length,
						// and no errors writing the file)
						// if not try to delete the partial file
						if (totalBytes != length) {
							// file transfer error
							outFile.delete();
						}
					}
				} else {
					// the sender and this client are the same so oops!
					System.err.println("Requested file from self.");
				}
			}
		}
	}

	public static void main(String[] args) {
		int listeningPort = -1;
		int serverPort = -1;
		String serverAddress = "localhost";

		if (args.length == 4) {
			if (args[0].equals("-l")) {
				listeningPort = Integer.valueOf(args[1]);
				if (args[2].equals("-p")) {
					serverPort = Integer.valueOf(args[3]);
				}
			}
			if (listeningPort > 0 && serverPort > 0) {
				ChatClient client = new ChatClient(serverAddress, serverPort, listeningPort);
				client.start();
			} else {
				System.err.println("Usage ChatClient -l portnumber1 -p portnumber2");
			}
		} else {
			System.err.println("Usage ChatClient -l portnumber1 -p portnumber2");
		}
	}
}