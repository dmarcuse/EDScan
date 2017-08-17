package me.apemanzilla.edscan;

import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.text.Text;
import lombok.SneakyThrows;

public class PluginView extends BorderPane implements Initializable {
	private final EDScan edscan;

	@FXML
	private TableView<Plugin> table;

	@FXML
	private TableColumn<Plugin, Boolean> enabledColumn;

	@FXML
	private TableColumn<Plugin, String> pluginColumn, descriptionColumn;

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
		enabledColumn.setCellValueFactory(p -> {
			SimpleBooleanProperty prop = new SimpleBooleanProperty();

			edscan.getConfig().bindAndSet("plugins." + p.getValue().getClass().getName() + ".enabled", prop, true);

			return prop;
		});

		pluginColumn.setCellValueFactory(p -> {
			StringBuilder s = new StringBuilder(p.getValue().toString());

			p.getValue().getSource().filter(c -> c.equals(EDScan.class.getProtectionDomain().getCodeSource()))
					.ifPresent(c -> s.append(" [built-in]"));

			return new ReadOnlyStringWrapper(s.toString());
		});

		descriptionColumn.setCellValueFactory(p -> new ReadOnlyStringWrapper(p.getValue().getDescription()));

		enabledColumn.setCellFactory(c -> new CheckBoxTableCell<>());

		descriptionColumn.setCellFactory(c -> new TableCell<Plugin, String>() {
			@Override
			protected void updateItem(String item, boolean empty) {
				super.updateItem(item, empty);
				if (item == null || empty) {
					setText(null);
				} else {
					Text text = new Text(item);
					text.wrappingWidthProperty().bind(getTableColumn().widthProperty().subtract(5));
					setGraphic(text);
				}
			}
		});

		table.setItems(FXCollections.observableArrayList(edscan.getPluginManager().getPlugins()));
	}

	@FXML
	private void openPluginFolder() {
		edscan.getHostServices().showDocument(edscan.getPluginDirectory().toUri().toString());
	}
}
