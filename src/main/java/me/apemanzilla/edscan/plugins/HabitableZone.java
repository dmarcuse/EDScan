package me.apemanzilla.edscan.plugins;

import static java.lang.Math.pow;

import java.io.IOException;
import java.net.URL;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import com.google.auto.service.AutoService;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import me.apemanzilla.edjournal.events.Scan.StarScan;
import me.apemanzilla.edscan.Plugin;

@AutoService(Plugin.class)
public class HabitableZone extends Plugin {
	@Override
	public String getName() {
		return "Habitable Zone Finder";
	}

	@Override
	public String getDescription() {
		return "Provides an estimation of the habitable range of any scanned stars";
	}

	@Override
	public Optional<Callable<Node>> getViewBuilder() {
		return Optional.ofNullable(HabitableZoneView::new);
	}

	private class HabitableZoneView extends VBox implements Initializable, Consumer<StarScan> {

		@FXML
		private Label system, habZoneInner, habZoneOuter;

		public HabitableZoneView() throws IOException {
			FXMLLoader loader = new FXMLLoader(HabitableZone.class.getResource("HabitableZone.fxml"));

			loader.setController(this);
			loader.setRoot(this);

			loader.load();
		}

		@Override
		public void initialize(URL location, ResourceBundle resources) {
			system.setText("Unknown Star");
			habZoneInner.setText("0");
			habZoneOuter.setText("0");

			edscan.getJournal().lastEventOfType(StarScan.class).ifPresent(this);
			edscan.addEventListener(StarScan.class, this);
		}

		private double distForBlackBodyTemp(double radius, double surfaceTemp, double targetTemp) {
			double top = pow(radius, 2) * pow(surfaceTemp, 4);
			double bottom = 4 * pow(targetTemp, 4);
			double radiusMeters = pow(top / bottom, 0.5);
			return radiusMeters / 300_000_000;
		}

		@Override
		public void accept(StarScan scan) {
			system.setText(String.format("%s (Class %s)", scan.getBodyName(), scan.getStarType()));

			double habInner = distForBlackBodyTemp(scan.getRadius(), scan.getSurfaceTemperature(), 315);
			double habOuter = distForBlackBodyTemp(scan.getRadius(), scan.getSurfaceTemperature(), 223);

			habZoneInner.setText(String.format("%.2f", habInner));
			habZoneOuter.setText(String.format("%.2f", habOuter));
		}
	}
}
