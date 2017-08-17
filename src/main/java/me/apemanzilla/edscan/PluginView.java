package me.apemanzilla.edscan;

import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import lombok.SneakyThrows;

public class PluginView extends BorderPane implements Initializable {
	private final EDScan edscan;

	@FXML
	private TableView table;

	@SneakyThrows(IOException.class)
	public PluginView(EDScan edscan) {
		this.edscan = edscan;

		FXMLLoader loader = new FXMLLoader(PluginView.class.getResource("PluginView.fxml"));

		loader.setController(this);
		loader.setRoot(this);

		loader.load();
	}

	@Override
	public void initialize(URL location, ResourceBundle resources) {

	}

	@FXML
	private void openPluginFolder() {
		edscan.getHostServices().showDocument(edscan.getPluginDirectory().toUri().toString());
	}
}
