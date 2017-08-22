package me.apemanzilla.edscan.plugins;

import static java.lang.Math.pow;

import java.util.Optional;
import java.util.concurrent.Callable;

import com.google.auto.service.AutoService;

import javafx.application.Platform;
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

	Label starName = new Label();
	Label starHabZone = new Label();

	private double distForBlackBodyTemp(double radius, double surfaceTemp, double targetTemp) {
		double top = pow(radius, 2) * pow(surfaceTemp, 4);
		double bottom = 4 * pow(targetTemp, 4);
		double radiusMeters = pow(top / bottom, 0.5);
		return radiusMeters / 300_000_000;
	}

	private void updateLabels(StarScan scan) {
		Platform.runLater(() -> {
			starName.setText(String.format("%s (Class %s)", scan.getBodyName(), scan.getStarType()));

			double habInner = distForBlackBodyTemp(scan.getRadius(), scan.getSurfaceTemperature(), 315);
			double habOuter = distForBlackBodyTemp(scan.getRadius(), scan.getSurfaceTemperature(), 223);

			starHabZone.setText(String.format("%.2fls to %.2fls", habInner, habOuter));
		});
	}

	@Override
	public void init() {
		edscan.getJournal().lastEventOfType(StarScan.class).ifPresent(this::updateLabels);
		edscan.addEventListener(StarScan.class, this::updateLabels);
	}
	
	@Override
	public Optional<Callable<Node>> getViewBuilder() {
		return Optional.ofNullable(() -> new VBox(starName, starHabZone));
	}
}
