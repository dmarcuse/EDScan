package me.apemanzilla.edscan;

import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import lombok.SneakyThrows;

public class AboutView extends VBox implements Initializable {
	@FXML
	private Label versionLabel;

	@SneakyThrows(IOException.class)
	public AboutView() {
		FXMLLoader loader = new FXMLLoader(EDScan.class.getResource("AboutView.fxml"));

		loader.setController(this);
		loader.setRoot(this);

		loader.load();
	}

	@Override
	public void initialize(URL location, ResourceBundle resources) {
		versionLabel.setText(EDScan.getVersion().map(v -> "Version " + v).orElse("Unknown Version"));
	}
}
