package me.apemanzilla.edscan.impl;

import static java.lang.Math.pow;

import java.util.function.Consumer;

import com.google.auto.service.AutoService;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import me.apemanzilla.edjournal.events.Scan.StarScan;
import me.apemanzilla.edscan.Plugin;

@AutoService(Plugin.class)
public class HabitableZone extends Plugin implements Consumer<StarScan> {
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

	public void accept(StarScan scan) {
		updateLabels(scan);
	}

	@Override
	public void init() {
		edscan.addView("Estimated Habitable Zone", new VBox(starName, starHabZone));
		edscan.getJournal().lastEventOfType(StarScan.class).ifPresent(this);
		edscan.addEventListener(StarScan.class, this);
	}

	@Override
	public void cleanup() {
		edscan.removeEventListener(this);
	}
}
