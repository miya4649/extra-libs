package synthesijer.utils;

import java.util.EnumSet;

import synthesijer.hdl.HDLModule;
import synthesijer.hdl.HDLPort;

public class FifoReadPort {
	
	public final HDLPort din;
	public final HDLPort re;
	public final HDLPort rclk;
	public final HDLPort empty;
	public final HDLPort length;
	public final HDLPort full;

	public FifoReadPort(HDLModule m, String prefix, int width){
		din = Utils.genInputPort(m, prefix + "din", width, EnumSet.of(HDLPort.OPTION.EXPORT));
		re = Utils.genOutputPort(m, prefix + "re", EnumSet.of(HDLPort.OPTION.EXPORT));
		rclk = Utils.genOutputPort(m, prefix + "rclk", EnumSet.of(HDLPort.OPTION.EXPORT));
		empty = Utils.genInputPort(m, prefix + "empty", EnumSet.of(HDLPort.OPTION.EXPORT));
		length = Utils.genInputPort(m, prefix + "length", 32, EnumSet.of(HDLPort.OPTION.EXPORT));
		full = Utils.genInputPort(m, prefix + "full", EnumSet.of(HDLPort.OPTION.EXPORT));
	}
}
