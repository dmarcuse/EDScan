package me.apemanzilla.edscan;

import java.io.PrintWriter;
import java.io.StringWriter;

import lombok.experimental.UtilityClass;

@UtilityClass
public class Utils {
	public String getStackTraceAsString(Throwable t) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		t.printStackTrace(pw);
		return sw.toString();
	}
}
