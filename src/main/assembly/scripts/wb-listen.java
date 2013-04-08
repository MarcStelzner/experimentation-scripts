//--------------------------------------------------------------------------
// Configuration
//--------------------------------------------------------------------------

	String localControllerEndpointURL	= "http://" + InetAddress.getLocalHost().getCanonicalHostName() + ":8091/controller";
	String secretReservationKeys        = System.getProperty("testbed.secretreservationkeys");
	String sessionManagementEndpointURL	= System.getProperty("testbed.sm.endpointurl");
	boolean csv                         = System.getProperty("testbed.listtype") != null && "csv".equals(System.getProperty("testbed.listtype"));

	String protobufHost                 = System.getProperty("testbed.protobuf.hostname");
	String protobufPortString           = System.getProperty("testbed.protobuf.port");
	Integer protobufPort                = protobufPortString == null ? null : Integer.parseInt(protobufPortString);
	boolean useProtobuf                 = protobufHost != null && protobufPort != null;

	SessionManagement sessionManagement = WSNServiceHelper.getSessionManagementService(sessionManagementEndpointURL);

//--------------------------------------------------------------------------
// Application logic
//--------------------------------------------------------------------------

	Controller controller = new Controller() {
		public void receive(List msgs) {
			for (int i=0; i<msgs.size(); i++) {
				Message msg = (Message) msgs.get(i);
				synchronized(System.out) {
					String msgString = StringUtils.toHexString(msg.getBinaryData());
					if(msgString.startsWith("0x6c")){	
						//a log needs to be formatted and printed
						System.out.println(unhex(msg));
					} else {
						String text = StringUtils.replaceNonPrintableAsciiCharacters(new String(msg.getBinaryData()));

						if (csv) {
							text = text.replaceAll(";", "\\;");
						}

						System.out.print(new org.joda.time.DateTime(msg.getTimestamp().toGregorianCalendar()));
						System.out.print(csv ? ";" : " | ");
						System.out.print(msg.getSourceNodeId());
						System.out.print(csv ? ";" : " | ");
						System.out.print(text);
						System.out.print(csv ? ";" : " | ");
						System.out.print(msgString);
						System.out.println();
					}
            	}
			}
		}
		public void receiveStatus(List requestStatuses) {
			// nothing to do
		}
		public void receiveNotification(List msgs) {
			for (int i=0; i<msgs.size(); i++) {
				System.err.print(new org.joda.time.DateTime());
				System.err.print(csv ? ";" : " | ");
				System.err.print("Notification");
				System.err.print(csv ? ";" : " | ");
				System.err.print(msgs.get(i));
				System.err.println();
			}
		}
		public void experimentEnded() {
			System.err.println("Experiment ended");
			System.exit(0);
		}
		private String unhex(Message msg){
			resultString = "";
			String msgString = StringUtils.toHexString(msg.getBinaryData());
			//is it a logmessage ? 108(int8) = 6c(hex) = l(ASCII)
			try{
				//now include node-address (only last 4 chars needed)
				resultString = msg.getSourceNodeId().substring(msg.getSourceNodeId().length()-4,msg.getSourceNodeId().length()) + ";";
				//include time
				String microseconds = "0000";
				long milliseconds = msg.getTimestamp().toGregorianCalendar().getTimeInMillis();
				long seconds = milliseconds/1000;
				milliseconds = milliseconds % 1000;
				String secondsString = Long.toHexString(seconds);
				String millisecondsString = Long.toHexString(milliseconds);
				while(secondsString.length() < 8){
					secondsString = "0"+secondsString;
				}
				while(millisecondsString.length() < 4){
					millisecondsString = "0"+millisecondsString;
				}
				//get it to 
				resultString += secondsString + ";" + millisecondsString + ";" + microseconds + ";";
				//Integer.toHexString(seconds)
				//include the rest of the nodes information
				//elide the starting element
				msgString = msgString.substring(4, msgString.length()) + " ";
				//elide all hex-signs, but add zeros //TODO: Do this more efficently
				msgString = msgString.replaceAll("x0 ","x00 ");
				msgString = msgString.replaceAll("x1 ","x01 ");
				msgString = msgString.replaceAll("x2 ","x02 ");
				msgString = msgString.replaceAll("x3 ","x03 ");
				msgString = msgString.replaceAll("x4 ","x04 ");
				msgString = msgString.replaceAll("x5 ","x05 ");
				msgString = msgString.replaceAll("x6 ","x06 ");
				msgString = msgString.replaceAll("x7 ","x07 ");
				msgString = msgString.replaceAll("x8 ","x08 ");
				msgString = msgString.replaceAll("x9 ","x09 ");
				msgString = msgString.replaceAll("xa ","x0a ");
				msgString = msgString.replaceAll("xb ","x0b ");
				msgString = msgString.replaceAll("xc ","x0c ");
				msgString = msgString.replaceAll("xd ","x0d ");
				msgString = msgString.replaceAll("xe ","x0e ");
				msgString = msgString.replaceAll("xf ","x0f ");
				msgString = msgString.replaceAll(" 0x","");
				//include seperation signs after element 3(source),7(destination),11(link_metric) and 15(len)
				// (couting from 0)
				msgString = msgString.substring(0,4)+";"+msgString.substring(4,8)+";"+msgString.substring(8,12)+";"+msgString.substring(12,16)+";"+msgString.substring(16,msgString.length());
				resultString += msgString;
				return resultString;
			}
			catch(Exception e){
				return "BAD FORMAT FOR LOGFILE!";
			}
		}
	};

	// try to connect via unofficial protocol buffers API if hostname and port are set in the configuration
    if (useProtobuf) {

		ProtobufControllerClient pcc = ProtobufControllerClient.create(
				protobufHost,
				protobufPort,
				helper.parseSecretReservationKeys(secretReservationKeys)
		);
		pcc.addListener(new ProtobufControllerAdapter(controller));
		try {
			pcc.connect();
		} catch (Exception e) {
			useProtobuf = false;
		}
	}

	if (!useProtobuf) {

		DelegatingController delegator = new DelegatingController(controller);
		delegator.publish(localControllerEndpointURL);
		log.debug("Local controller published on url: {}", localControllerEndpointURL);

	}

	log.debug("Using the following parameters for calling getInstance(): {}, {}",
			StringUtils.jaxbMarshal(helper.parseSecretReservationKeys(secretReservationKeys)),
			localControllerEndpointURL
	);

	String wsnEndpointURL = null;
	try {
		wsnEndpointURL = sessionManagement.getInstance(
				helper.parseSecretReservationKeys(secretReservationKeys),
				(useProtobuf ? "NONE" : localControllerEndpointURL)
		);
	} catch (UnknownReservationIdException_Exception e) {
		log.warn("There was no reservation found with the given secret reservation key. Exiting.");
		System.exit(1);
	}

	log.debug("Got a WSN instance URL, endpoint is: {}", wsnEndpointURL);
	WSN wsnService = WSNServiceHelper.getWSNService(wsnEndpointURL);
	final WSNAsyncWrapper wsn = WSNAsyncWrapper.of(wsnService);

	while(true) {
		try {
			System.in.read();
		} catch (Exception e) {
			System.err.println(e);
		}
	}
