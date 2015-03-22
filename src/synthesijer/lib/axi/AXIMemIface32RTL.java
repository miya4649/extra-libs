package synthesijer.lib.axi;


import java.util.EnumSet;

import synthesijer.hdl.HDLExpr;
import synthesijer.hdl.HDLInstance;
import synthesijer.hdl.HDLModule;
import synthesijer.hdl.HDLOp;
import synthesijer.hdl.HDLPort;
import synthesijer.hdl.HDLPrimitiveType;
import synthesijer.hdl.HDLSequencer;
import synthesijer.hdl.HDLSignal;
import synthesijer.hdl.HDLUtils;
import synthesijer.hdl.expr.HDLPreDefinedConstant;
import synthesijer.hdl.sequencer.SequencerState;
import synthesijer.lib.BlockRAM;
import synthesijer.utils.Utils;

public class AXIMemIface32RTL extends HDLModule{
	
	/////////////////////////////////////////////////////////////////
	// dummy variables, for Synthesijer /////////////////////////////
	public boolean busy;
	public boolean write_kick;
	public boolean read_kick;
	public int data[];
	public int axi_addr;
	public int burst_size;
	public int read_result;
	/////////////////////////////////////////////////////////////////

	/////////////////////////////////////////////////////////////////
	// RTL implementation
	
	public final HDLPort addr, wdata, rdata, we, oe;
	public final HDLPort hdl_axi_addr, hdl_busy, hdl_write_kick, hdl_read_kick, hdl_burst_size;
	public final HDLPort forbid;
	
	public final AxiMasterPort axi;
	public final HDLSignal local_addr, local_wdata, local_rdata, local_we, local_oe;
	
	private final HDLSignal read_state_busy;
	private final HDLSignal write_state_busy;
	
	private final HDLInstance buf;
	
	private final HDLExpr hdl_write_kick_edge;
	private final HDLExpr hdl_read_kick_edge;
		
	public AXIMemIface32RTL(String... args){
		super("axi_memiface_32", "clk", "reset");
		
		addr = Utils.genInputPort(this, "data_address", 32);
		wdata = Utils.genInputPort(this, "data_din", 32);
		rdata = Utils.genOutputPort(this, "data_dout", 32);
		we = Utils.genInputPort(this, "data_we");
		oe = Utils.genInputPort(this, "data_oe");
		HDLPort length = Utils.genOutputPort(this, "data_length", 32);
		hdl_busy = Utils.genOutputPort(this, "busy");
		hdl_write_kick = Utils.genInputPort(this, "write_kick");
		hdl_read_kick = Utils.genInputPort(this, "read_kick");
		hdl_burst_size = Utils.genInputPort(this, "burst_size", 32);
		hdl_axi_addr = Utils.genInputPort(this, "axi_addr", 32);
		forbid = Utils.genInputPort(this, "forbid", EnumSet.of(HDLPort.OPTION.EXPORT));
		
		axi = new AxiMasterPort(this, "axi", 32, 512*1024*1024);
		
		HDLModule ram = new BlockRAM(32, 10, 1024);
		buf = newModuleInstance(ram, "BUF");
		buf.getSignalForPort(ram.getSysClkName()).setAssign(null, getSysClk().getSignal());
		buf.getSignalForPort(ram.getSysResetName()).setAssign(null, getSysReset().getSignal());
		// for Java-side connection
		buf.getSignalForPort("address").setAssign(null, addr.getSignal());
		buf.getSignalForPort("din").setAssign(null, wdata.getSignal());
		buf.getSignalForPort("we").setAssign(null, we.getSignal());
		buf.getSignalForPort("oe").setAssign(null, oe.getSignal());
		rdata.getSignal().setAssign(null, buf.getSignalForPort("dout"));
		length.getSignal().setAssign(null, buf.getSignalForPort("length"));
		// for internal connection
		local_addr  = buf.getSignalForPort("address_b");
		local_wdata = buf.getSignalForPort("din_b");
		local_we    = buf.getSignalForPort("we_b");
		local_oe    = buf.getSignalForPort("oe_b");
		local_rdata = buf.getSignalForPort("dout_b");
		
		read_state_busy = newSignal("read_state_busy", HDLPrimitiveType.genBitType());
		write_state_busy = newSignal("write_state_busy", HDLPrimitiveType.genBitType());
		
		hdl_busy.getSignal().setAssign(null, newExpr(HDLOp.OR, newExpr(HDLOp.OR, write_state_busy, read_state_busy), forbid.getSignal()));
		
		HDLSignal hdl_write_kick_d = newSignal("hdl_write_kick_d", HDLPrimitiveType.genBitType());
		hdl_write_kick_edge = newExpr(HDLOp.AND, hdl_write_kick.getSignal(), newExpr(HDLOp.NOT, hdl_write_kick_d));
		
		HDLSignal hdl_read_kick_d = newSignal("hdl_read_kick_d", HDLPrimitiveType.genBitType());
		hdl_read_kick_edge = newExpr(HDLOp.AND, newExpr(HDLOp.NOT, hdl_write_kick_edge), 
				                                newExpr(HDLOp.AND, hdl_read_kick.getSignal(), newExpr(HDLOp.NOT, hdl_read_kick_d)));
				
		HDLSequencer seq = newSequencer("axi");
		seq.addSeqExpr(hdl_write_kick_d, hdl_write_kick.getSignal());
		seq.addSeqExpr(hdl_read_kick_d, hdl_read_kick.getSignal());
		genWriteSeq(seq);
		genReadSeq(seq);
	}
	
	private void genWriteSeq(HDLSequencer seq){
		axi.writer.bready.getSignal().setAssign(seq.getIdleState(), HDLPreDefinedConstant.HIGH);
		HDLSignal count = newSignal("write_count", HDLPrimitiveType.genSignedType(32), HDLSignal.ResourceKind.REGISTER);
		HDLExpr local_addr_inc = newExpr(HDLOp.ADD, local_addr, 1);
		HDLSignal buf0 = newSignal("local_rdata_buf0", HDLPrimitiveType.genSignedType(32), HDLSignal.ResourceKind.REGISTER);
		HDLSignal buf1 = newSignal("local_rdata_buf1", HDLPrimitiveType.genSignedType(32), HDLSignal.ResourceKind.REGISTER);
		HDLExpr count_dec = newExpr(HDLOp.SUB, count, 1);
		HDLSignal buf1_null_flag = newSignal("buf1_null_flag", HDLPrimitiveType.genBitType(), HDLSignal.ResourceKind.REGISTER);
		buf1_null_flag.setDefaultValue(HDLPreDefinedConstant.HIGH);
		
		// IDLE
		axi.writer.awaddr.getSignal().setAssign(seq.getIdleState(), hdl_axi_addr.getSignal());
		axi.writer.awlen.getSignal().setAssign(seq.getIdleState(), newExpr(HDLOp.DROPHEAD, newExpr(HDLOp.SUB, hdl_burst_size.getSignal(), 1), Utils.value(24, 32))); // bust_size - 1
		axi.writer.awvalid.getSignal().setAssign(seq.getIdleState(), hdl_write_kick_edge); // kick axi_writer
		write_state_busy.setAssign(seq.getIdleState(), hdl_write_kick_edge);
		local_addr.setAssign(seq.getIdleState(), HDLPreDefinedConstant.VECTOR_ZERO);
		local_oe.setAssign(seq.getIdleState(), hdl_write_kick_edge); 
		count.setAssign(seq.getIdleState(), hdl_burst_size.getSignal());
		axi.writer.wvalid.getSignal().setAssign(seq.getIdleState(), HDLPreDefinedConstant.LOW); // de-assert, just after wready is asserted.
		axi.writer.wlast.getSignal().setAssign(seq.getIdleState(), HDLPreDefinedConstant.LOW); // de-assert, just after wready is asserted.
		SequencerState s0 = seq.addSequencerState("write_s0");
		seq.getIdleState().addStateTransit(hdl_write_kick_edge, s0);
		
		// s0, wait for awready
		axi.writer.awvalid.getSignal().setAssign(s0, newExpr(HDLOp.NOT, axi.writer.awready.getSignal())); // de-assert, just after awready is asserted.
		local_addr.setAssign(s0, newExpr(HDLOp.IF, axi.writer.awready.getSignal(), local_addr_inc, local_addr)); // read_ptr++ 
		SequencerState s1 = seq.addSequencerState("write_s1");
		s0.addStateTransit(axi.writer.awready.getSignal(), s1);
		
		// s1, bram latency
		buf0.setAssign(s1, local_rdata);
		local_addr.setAssign(s1, local_addr_inc); // read_ptr++ 
		SequencerState s2 = seq.addSequencerState("write_s2");
		s1.addStateTransit(s2);

		// s2, bram latency
		buf0.setAssign(s2, local_rdata);
		local_addr.setAssign(s2, local_addr_inc); // read_ptr++ 
		axi.writer.wvalid.getSignal().setAssign(s2, HDLPreDefinedConstant.HIGH); 
		axi.writer.wlast.getSignal().setAssign(s2, newExpr(HDLOp.IF, newExpr(HDLOp.EQ, count, 1), HDLPreDefinedConstant.HIGH, HDLPreDefinedConstant.LOW)); 
		axi.writer.wdata.getSignal().setAssign(s2, buf0); 
		SequencerState s3 = seq.addSequencerState("write_s3");
		s2.addStateTransit(s3);

		// s2, start to write data
		axi.writer.wlast.getSignal().setAssign(s3,
				newExpr(HDLOp.IF,
						newExpr(HDLOp.EQ, count, 1),
						// count == 1
						newExpr(HDLOp.IF,
								axi.writer.wready.getSignal(),
								HDLPreDefinedConstant.LOW, // last data are accepted 
								HDLPreDefinedConstant.HIGH), // accepted are not accepted yet
						// count != 1
						newExpr(HDLOp.IF,
								newExpr(HDLOp.EQ, count, 2),
								// count == 2
								newExpr(HDLOp.IF,
										axi.writer.wready.getSignal(),
										HDLPreDefinedConstant.HIGH, // in next, last word
										HDLPreDefinedConstant.LOW), // in next, not last word
								// count != 2
								HDLPreDefinedConstant.LOW)));
		axi.writer.wvalid.getSignal().setAssign(s3,
				newExpr(HDLOp.IF,
						newExpr(HDLOp.GT, count, 1),
						HDLPreDefinedConstant.HIGH,
						newExpr(HDLOp.IF,
								newExpr(HDLOp.AND, newExpr(HDLOp.EQ, count, 1), axi.writer.wready.getSignal()),
								HDLPreDefinedConstant.LOW,
								HDLPreDefinedConstant.HIGH))); // de-assert, just after wready is asserted.
		axi.writer.wdata.getSignal().setAssign(s3, newExpr(HDLOp.IF, axi.writer.wready.getSignal(), buf0, axi.writer.wdata.getSignal()));
		buf0.setAssign(s3, newExpr(HDLOp.IF, axi.writer.wready.getSignal(), buf1, buf0));
		buf1.setAssign(s3, newExpr(HDLOp.IF, newExpr(HDLOp.OR, axi.writer.wready.getSignal(), buf1_null_flag), local_rdata, buf1));
		local_addr.setAssign(s3, newExpr(HDLOp.IF, newExpr(HDLOp.OR, axi.writer.wready.getSignal(), buf1_null_flag), local_addr_inc, local_addr)); // read_ptr++ 
		buf1_null_flag.setAssign(s3, HDLPreDefinedConstant.LOW);
		count.setAssign(s3, newExpr(HDLOp.IF, newExpr(HDLOp.AND, axi.writer.wready.getSignal(), newExpr(HDLOp.GT, count, 0)), count_dec, count));
		
		SequencerState s4 = seq.addSequencerState("write_s4");
		s3.addStateTransit(newExpr(HDLOp.AND, newExpr(HDLOp.EQ, count, 1), axi.writer.wready.getSignal()), s4);
		
		// s2
		s4.addStateTransit(axi.writer.bvalid.getSignal(), seq.getIdleState());
		axi.writer.wlast.getSignal().setAssign(s4, HDLPreDefinedConstant.LOW);
		axi.writer.wvalid.getSignal().setAssign(s4, HDLPreDefinedConstant.LOW);
	}
		
	private void genReadSeq(HDLSequencer seq){
		axi.reader.rready.getSignal().setAssign(seq.getIdleState(), HDLPreDefinedConstant.HIGH);
		HDLSignal count = newSignal("read_count", HDLPrimitiveType.genSignedType(32), HDLSignal.ResourceKind.REGISTER);
		HDLSignal addr_next = newSignal("addr_next", HDLPrimitiveType.genSignedType(32), HDLSignal.ResourceKind.REGISTER);
		
		// IDLE
		axi.reader.arvalid.getSignal().setAssign(seq.getIdleState(), hdl_read_kick_edge); // kick axi_reader
		axi.reader.araddr.getSignal().setAssign(seq.getIdleState(), hdl_axi_addr.getSignal());
		axi.reader.arlen.getSignal().setAssign(seq.getIdleState(), newExpr(HDLOp.DROPHEAD, newExpr(HDLOp.SUB, hdl_burst_size.getSignal(), 1), Utils.value(24, 32))); // bust_size - 1
		SequencerState s0 = seq.addSequencerState("read_s0");
		count.setAssign(seq.getIdleState(), hdl_burst_size.getSignal());
		local_addr.setAssign(seq.getIdleState(), HDLPreDefinedConstant.VECTOR_ZERO);
		addr_next.setAssign(seq.getIdleState(), HDLPreDefinedConstant.VECTOR_ZERO);
		seq.getIdleState().addStateTransit(hdl_read_kick_edge, s0); // idle -> s0, when oe = '1'
		read_state_busy.setAssign(seq.getIdleState(), hdl_read_kick_edge); // to start read sequence
		local_we.setDefaultValue(HDLPreDefinedConstant.LOW);
		
		// S0
		axi.reader.arvalid.getSignal().setAssign(s0, newExpr(HDLOp.NOT, axi.reader.arready.getSignal()));
		SequencerState s1 = seq.addSequencerState("read_s1");
		s0.addStateTransit(axi.reader.arready.getSignal(), s1); // s0 -> s1, when arready = '1'
		
		HDLExpr last_word = newExpr(HDLOp.EQ, count, 1);
		HDLExpr count_dec = newExpr(HDLOp.SUB, count, 1);
		HDLExpr addr_next_inc = newExpr(HDLOp.ADD, addr_next, 1);
		
		// S1
		local_wdata.setAssign(s1, axi.reader.rdata.getSignal());
		local_addr.setAssign(s1, addr_next);
		local_we.setAssign(s1, axi.reader.rvalid.getSignal());
		count.setAssign(s1, newExpr(HDLOp.IF, axi.reader.rvalid.getSignal(), count_dec, count));
		addr_next.setAssign(s1, newExpr(HDLOp.IF, axi.reader.rvalid.getSignal(), addr_next_inc, addr_next));
		s1.addStateTransit(newExpr(HDLOp.AND, axi.reader.rvalid.getSignal(), last_word), seq.getIdleState());
	}
	public static void main(String... args){
		AXIMemIface32RTL m = new AXIMemIface32RTL();
		HDLUtils.genHDLSequencerDump(m);
		//HDLUtils.genResourceUsageTable(m);
		HDLUtils.generate(m, HDLUtils.VHDL);
		HDLUtils.generate(m, HDLUtils.Verilog);
		//HDLUtils.generate(new SimpleAXIMemIface32RTL_Sim(m), HDLUtils.VHDL);
	}

}
