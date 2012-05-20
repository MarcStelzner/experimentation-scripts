//--------------------------------------------------------------------------
// Configuration
//--------------------------------------------------------------------------

	String localControllerEndpointURL   = "http://" + InetAddress.getLocalHost().getCanonicalHostName() + ":8089/controller";
	String secretReservationKeys        = System.getProperty("testbed.secretreservationkeys");
	String imageToFlashPath             = System.getProperty("testbed.image");
	String nodeUrnsToFlash              = System.getProperty("testbed.nodeurns");
	boolean csv                         = System.getProperty("testbed.listtype") != null && "csv".equals(System.getProperty("testbed.listtype"));

	String protobufHost                 = System.getProperty("testbed.protobuf.hostname");
	String protobufPortString           = System.getProperty("testbed.protobuf.port");
	Integer protobufPort                = protobufPortString == null ? null : Integer.parseInt(protobufPortString);
	boolean useProtobuf                 = protobufHost != null && protobufPort != null;

	String sessionManagementEndpointURL	= System.getProperty("testbed.sm.endpointurl");
	SessionManagement sessionManagement = WisebedServiceHelper.getSessionManagementService(sessionManagementEndpointURL);

//--------------------------------------------------------------------------
// Application logic
//--------------------------------------------------------------------------

	log.debug("Using the following parameters for calling getInstance(): {}, {}",
			StringUtils.jaxbMarshal(helper.parseSecretReservationKeys(secretReservationKeys)),
			localControllerEndpointURL
	);

	Controller controller = new Controller() {
		public void receive(List msg) {
			// nothing to do
		}
		public void receiveStatus(List requestStatuses) {
			wsn.receive(requestStatuses);
		}
		public void receiveNotification(List msgs) {
			for (int i=0; i<msgs.size(); i++) {
				log.info(msgs.get(i));
			}
		}
		public void experimentEnded() {
			log.info("Experiment ended");
			System.exit(0);
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
	WSN wsnService = WisebedServiceHelper.getWSNService(wsnEndpointURL);
	final WSNAsyncWrapper wsn = WSNAsyncWrapper.of(wsnService);

	// retrieve reserved node URNs from testbed
	List nodeURNs;
	if (nodeUrnsToFlash != null && !"".equals(nodeUrnsToFlash)) {
		nodeURNs = Lists.newArrayList(nodeUrnsToFlash.split(","));
		log.debug("Selected node URNs: {}", nodeURNs);
	} else {
		nodeURNs = WiseMLHelper.getNodeUrns(wsn.getNetwork().get(), new String[]{});
		log.debug("Reserved node URNs: {}", nodeURNs);
	}

    log.debug("Flashing nodes...");

	List programIndices;
	List programs;

	// wb-flash isense nodes
    programIndices = new ArrayList();
    programs = new ArrayList();
    for (int i=0; i<nodeURNs.size(); i++) {
        programIndices.add(0);
    }
    programs.add(helper.readProgram(
            imageToFlashPath,
            "",
            "",
            "iSense",
            "1.0"
    ));

	Future flashFuture = wsn.flashPrograms(nodeURNs, programIndices, programs, 3, TimeUnit.MINUTES);
	try {

		JobResult flashJobResult = flashFuture.get();
		flashJobResult.printResults(System.out, csv);
		System.exit(flashJobResult.getSuccessPercent() < 100 ? 1 : 0);

	} catch (ExecutionException e) {
		if (e.getCause() instanceof TimeoutException) {
			log.info("Call timed out. Exiting...");
		}
		System.exit(1);
	}
