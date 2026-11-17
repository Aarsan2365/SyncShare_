package com.syncshare.ui;

import com.syncshare.discovery.PeerInfo;
import com.syncshare.model.IncomingRequestItem;
import com.syncshare.monitoring.MonitoredTransfer;
import com.syncshare.service.SyncShareServices;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SimpleSyncShare extends Application {

    private SyncShareServices services;
    private Timeline refreshTimer;

    private final TextField receiverIp = new TextField();
    private final TextField receiverPort = new TextField("5050");
    private final TextField selectedFile = new TextField();

    private final Label connectionStatus = new Label();
    private final Label receiveFolder = new Label();
    private final Label message = new Label("Ready");

    private final Button receiverButton = new Button("Start Receiver");

    private final ListView<PeerInfo> peers = new ListView<>();
    private final ListView<IncomingRequestItem> requests = new ListView<>();
    private final ListView<String> transfers = new ListView<>();

    @Override
    public void start(Stage stage) {
        services = SyncShareServices.createDefault();

        var device = services.deviceInfoService().getDeviceInfo();

        Label title = new Label("SyncShare");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");

        Label localInfo = new Label(
                "Your IP: " + device.ipAddress()
                + "    Receiver port: " + device.listeningPort()
        );

        receiverButton.setOnAction(event -> toggleReceiver());

        Button folderButton = new Button("Receive Folder");
        folderButton.setOnAction(event -> chooseReceiveFolder(stage));

        HBox connectionControls = new HBox(
                10, receiverButton, folderButton
        );

        connectionStatus.setWrapText(true);
        receiveFolder.setWrapText(true);

        configurePeers();
        configureRequests();

        receiverIp.setPromptText("Receiver IP address");
        receiverPort.setPrefWidth(90);
        receiverPort.setMaxWidth(90);
        HBox.setHgrow(receiverIp, Priority.ALWAYS);

        HBox addressRow = new HBox(
                10, receiverIp, new Label("Port"), receiverPort
        );

        selectedFile.setEditable(false);
        selectedFile.setPromptText("Choose a file");
        HBox.setHgrow(selectedFile, Priority.ALWAYS);

        Button chooseButton = new Button("Choose File");
        chooseButton.setOnAction(event -> {
            FileChooser chooser = new FileChooser();
            File file = chooser.showOpenDialog(stage);
            if (file != null) {
                selectedFile.setText(file.getAbsolutePath());
            }
        });

        Button sendButton = new Button("Send");
        sendButton.setOnAction(event -> sendFile());

        HBox fileRow = new HBox(
                10, selectedFile, chooseButton, sendButton
        );

        Button acceptButton = new Button("Accept");
        acceptButton.setOnAction(event -> decideRequest(true));

        Button rejectButton = new Button("Reject");
        rejectButton.setOnAction(event -> decideRequest(false));

        requests.setPrefHeight(85);
        peers.setPrefHeight(85);
        transfers.setPrefHeight(150);

        message.setWrapText(true);

        VBox root = new VBox(
                10,
                title,
                localInfo,
                connectionControls,
                connectionStatus,
                receiveFolder,
                new Separator(),
                new Label("Nearby devices — select one to fill its address"),
                peers,
                new Label("Send a file"),
                addressRow,
                fileRow,
                new Separator(),
                new Label("Incoming requests"),
                requests,
                new HBox(10, acceptButton, rejectButton),
                new Label("Transfers — progress, speed and result"),
                transfers,
                message
        );

        root.setPadding(new Insets(20));
        root.setStyle(
                "-fx-font-family: Arial;"
                + "-fx-font-size: 13px;"
                + "-fx-background-color: #f5f5f5;"
        );

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);

        stage.setScene(new Scene(scroll, 760, 780));
        stage.setTitle("SyncShare");
        stage.setMinWidth(600);
        stage.setMinHeight(500);
        stage.show();

        refreshTimer = new Timeline(
                new KeyFrame(Duration.millis(500), event -> refreshStatus())
        );
        refreshTimer.setCycleCount(Timeline.INDEFINITE);
        refreshTimer.play();

        refreshStatus();
    }

    private void configurePeers() {
        peers.setItems(services.discoveryManager().getDiscoveredPeers());

        peers.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(PeerInfo peer, boolean empty) {
                super.updateItem(peer, empty);
                setText(empty || peer == null ? null :
                        peer.name() + " — " + peer.address()
                        + ":" + peer.transferPort());
            }
        });

        peers.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldPeer, peer) -> {
                    if (peer != null) {
                        receiverIp.setText(peer.address());
                        receiverPort.setText(
                                Integer.toString(peer.transferPort())
                        );
                    }
                });
    }

    private void configureRequests() {
        requests.setItems(
                services.connectionService().getPendingRequests()
        );

        requests.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(
                    IncomingRequestItem request, boolean empty
            ) {
                super.updateItem(request, empty);
                setText(empty || request == null ? null :
                        request.fileName() + " — " + request.formattedSize()
                        + " from " + request.senderAddress());
            }
        });
    }

    private void toggleReceiver() {
        var connection = services.connectionService();

        if (connection.isListening()) {
            connection.stopReceiver();
            message.setText("Receiver stopped.");
        } else {
            boolean started = connection.startReceiver();
            message.setText(started
                    ? "Receiver started. Waiting for files."
                    : connection.getStatus().detail());
        }

        refreshStatus();
    }

    private void chooseReceiveFolder(Stage stage) {
        if (services.connectionService().isListening()) {
            message.setText("Stop the receiver before changing its folder.");
            return;
        }

        DirectoryChooser chooser = new DirectoryChooser();
        File folder = chooser.showDialog(stage);
        if (folder == null) {
            return;
        }

        var result = services.connectionService()
                .createAndValidateDestination(folder.getAbsolutePath());

        message.setText(result.valid()
                ? "Receive folder selected."
                : result.message());

        refreshStatus();
    }

    private void sendFile() {
        var validation = services.transferService().validateSendInput(
                receiverIp.getText(),
                receiverPort.getText(),
                selectedFile.getText()
        );

        if (!validation.valid()) {
            message.setText(String.join(" ",
                    validation.peerMessage(),
                    validation.portMessage(),
                    validation.fileMessage()).trim());
            return;
        }

        if (!services.deviceInfoService().isTlsConfigured()) {
            message.setText(
                    "Set your TLS password and configure certificates, "
                    + "then restart SyncShare."
            );
            return;
        }

        String host = receiverIp.getText().trim();

        if (services.transferService().hasActiveOutgoingTransfer(
                host, validation.port(), validation.file())) {
            message.setText("This file is already being sent to that receiver.");
            return;
        }

        services.transferService().sendFile(
                host, validation.port(), validation.file()
        );

        message.setText("Transfer queued. The receiver may need to accept.");
    }

    private void decideRequest(boolean accepted) {
        IncomingRequestItem request =
                requests.getSelectionModel().getSelectedItem();

        if (request == null) {
            message.setText("Select an incoming request first.");
            return;
        }

        boolean decided = services.connectionService()
                .approveRequest(request.requestId(), accepted);

        message.setText(decided
                ? (accepted ? "Request accepted." : "Request rejected.")
                : "Request expired or was already handled.");
    }

    private void refreshStatus() {
        var connection = services.connectionService();
        var status = connection.getStatus();

        receiverButton.setText(connection.isListening()
                ? "Stop Receiver" : "Start Receiver");

        connectionStatus.setText(
                status.headline() + " — " + status.detail()
        );

        receiveFolder.setText("Receive folder: "
                + services.settingsService().getSettings().receiveDirectory());

        List<String> rows = new ArrayList<>();

        for (MonitoredTransfer transfer :
                services.transferMonitoringService().getActiveSnapshot()) {
            rows.add(formatTransfer(transfer));
        }

        for (MonitoredTransfer transfer :
                services.transferMonitoringService().getRecentSnapshot()) {
            rows.add(formatTransfer(transfer));
        }

        if (!transfers.getItems().equals(rows)) {
            transfers.getItems().setAll(rows);
        }
    }

    private String formatTransfer(MonitoredTransfer transfer) {
        String text = transfer.direction() + " | "
                + transfer.fileName() + " | "
                + transfer.formattedPercentage() + " | "
                + transfer.formattedTransferred() + " / "
                + transfer.formattedTotalSize() + " | "
                + transfer.formattedSpeed() + " | "
                + transfer.state();

        if (!transfer.errorMessage().isBlank()) {
            text += " | " + transfer.errorMessage();
        }

        return text;
    }

    @Override
    public void stop() {
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
        if (services != null) {
            services.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}