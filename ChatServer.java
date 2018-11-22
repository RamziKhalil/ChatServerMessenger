import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class ChatServer extends Thread {

	public static final boolean _debug = true;

	public static final int BLOCK_SIZE = 4096;

	private final int serverPort;
	private final List<ClientHandlerThread> clients = new ArrayList<>();

	private boolean keepRunning = true;

	public ChatServer(int serverPort) {
		this.serverPort = serverPort;
	}

	@Override
	public void run() {
		try (ServerSocket serverSock = new ServerSocket(this.serverPort)) {
			// start listening for new client connections
			serverSock.setSoTimeout(10000);
			while (keepRunning) {
				try {
					Socket newConnection = serverSock.accept();
					if (newConnection != null) {
						// start a new client handler thread to listen to this client's requests
						ClientHandlerThread client = new ClientHandlerThread(newConnection);
						clients.add(client);
						client.start();
					}
				} catch (SocketTimeoutException e) {
				}
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private synchronized Collection<ClientHandlerThread> getClients() {
		return Collections.unmodifiableList(new ArrayList<>(this.clients));
	}

	private synchronized void removeClient(String clientName) {
		// find the client from our list
		for (ClientHandlerThread client : this.clients) {
			if (clientName.equals(client.clientName)) {
				this.clients.remove(client);
				return;
			}
		}
	}

	private synchronized ClientHandlerThread getClient(String clientName) {
		// find the client from our list
		for (ClientHandlerThread client : this.clients) {
			if (clientName.equals(client.clientName)) {
				return client;
			}
		}
		return null;
	}

	private class ClientHandlerThread extends Thread {

		private final Socket clientSocket;
		String clientName;
		String fileRequestAddress;
		int fileRequestPort;
		PrintWriter clientWriter;
		private final List<FileRelayHandler> relays = new ArrayList<>();

		public ClientHandlerThread(Socket clientSocket) {
			this.clientSocket = clientSocket;
		}

		@Override
		public void run() {
			try (BufferedReader clientReader = new BufferedReader(
					new InputStreamReader(clientSocket.getInputStream()));) {
				// create a shared print writer for this socket
				this.clientWriter = new PrintWriter(clientSocket.getOutputStream());

				// read the client intro message;
				String introLine = clientReader.readLine();

				// parse this line to get the client's name and fileRequestPort
				String introToks[] = introLine.split(":");

				clientName = introToks[1];
				fileRequestAddress = introToks[2];
				fileRequestPort = Integer.parseInt(introToks[3]);

				// start the client handler main loop
				clientHandlerMainLoop(clientReader);

			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} finally {
				if (clientSocket != null && !clientSocket.isClosed()) {
					try {
						clientSocket.close();
					} catch (Exception e) {
					}
				}
			}
		}

		private void clientHandlerMainLoop(BufferedReader clientReader) throws IOException {
			String inMessageLine;
			while ((inMessageLine = clientReader.readLine()) != null) {
				// break the message into tokens
				String msgToks[] = inMessageLine.split(":");

				char op = msgToks[0].charAt(0);
				String sender = msgToks[1];

				if (sender != null && ChatServer.this.getClient(sender) != null) {

					// switch on the message type
					if (op == 'm') {
						String msg = msgToks[2];

						Collection<ClientHandlerThread> clients = ChatServer.this.getClients();
						for (ClientHandlerThread client : clients) {
							if (!sender.equals(client.clientName)) {
								// send the message
								client.clientWriter.println("m:" + sender + ":" + msg);
								client.clientWriter.flush();
							}
						}
					} else if (op == 'f') {
						String options = msgToks[2];
						String owner = msgToks[3];
						String filepath = msgToks[4];

						if ("relay".equals(options)) {
							// step 1 - find the file owner client
							ClientHandlerThread fileOwnerClient = ChatServer.this.getClient(owner);

							// step 2 - find the file requestor client
							ClientHandlerThread fileRequestorClient = ChatServer.this.getClient(sender);

							if (fileRequestorClient != null && fileOwnerClient != null) {
								// start a new file relay thread
								FileRelayHandler relay = new FileRelayHandler(fileRequestorClient, fileOwnerClient,
										filepath);

								this.relays.add(relay);
								relay.start();
							} else {
								// TODO - in case either of the clients cannot be found
							}
						}
					} else if (op == 'x') {
						// get the sender client thread
						ClientHandlerThread exitingClient = ChatServer.this.getClient(sender);

						if (exitingClient != null) {
							// return an x to the client
							exitingClient.clientWriter.println("x:" + sender);
							exitingClient.clientWriter.flush();

							// remove the client from the client list
							ChatServer.this.removeClient(sender);

							// break out of the reading loop
							break;
						}
					} else {

					}
				}
			}
		}
	}

	private class FileRelayHandler extends Thread {
		private final ClientHandlerThread fileRequestorClient;
		private final ClientHandlerThread fileOwnerClient;
		private final String filePath;

		public FileRelayHandler(ClientHandlerThread fileRequestorClient, ClientHandlerThread fileOwnerClient,
				String filePath) {

			this.fileRequestorClient = fileRequestorClient;
			this.fileOwnerClient = fileOwnerClient;
			this.filePath = filePath;
		}
		
		@Override
		public void run() {
			// open a socket for both the requestor client and the owner client
			try (Socket requestorSocket = new Socket(fileRequestorClient.fileRequestAddress,
					fileRequestorClient.fileRequestPort);
					DataOutputStream requestorOutStream = new DataOutputStream(requestorSocket.getOutputStream());

					Socket ownerSocket = new Socket(fileOwnerClient.fileRequestAddress,
							fileOwnerClient.fileRequestPort);
					DataOutputStream ownerOutStream = new DataOutputStream(ownerSocket.getOutputStream());
					DataInputStream ownerInStream = new DataInputStream(ownerSocket.getInputStream());

			) {
				// send a transmit message to the owner - "t:<filePath>"
				ownerOutStream.writeUTF("t:" + filePath);
				ownerOutStream.flush();

				// send a receive message to the requestor - "r:<filePath>"
				requestorOutStream.writeUTF("r:" + filePath);
				requestorOutStream.flush();

				// first send the message length/status line
				String statusMsg = ownerInStream.readUTF();
				
				// forward this message to the requestor
				requestorOutStream.writeUTF(statusMsg);
				
				// parse the status message
				String statusToks[] = statusMsg.split(":");
				
				if("l".equals(statusToks[0])) {
					int length = Integer.parseInt(statusToks[3]);
					
					int totalBytes = 0;
					int nread;
					byte buffer[] = new byte[ChatServer.BLOCK_SIZE];

					// if status ok start reading from the owner client the requested file in blocks
					while ((nread = ownerInStream.read(buffer))!=-1) {
						// send that data to the requestor
						requestorOutStream.write(buffer, 0, nread);
						totalBytes += nread;
					}
					requestorOutStream.flush();
					
					if(totalBytes != length) {
						if(_debug) System.out.println("Error relaying file: sent " + totalBytes + " of " + length);
					}
				}

			} catch (UnknownHostException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			// close both sockets
		}

	}

	public static void main(String[] args) {
		if (args.length == 1) {
			int serverPort = Integer.valueOf(args[0]);
			ChatServer cs = new ChatServer(serverPort);
			cs.start();
		} else {
			System.err.println("Usage is Java ChatServer portnumber.");
		}
	}

}
