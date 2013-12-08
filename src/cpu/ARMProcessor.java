package cpu;

import static cpu.ARMDataOpCode.ADC;
import static cpu.ARMDataOpCode.ADD;
import static cpu.ARMDataOpCode.AND;
import static cpu.ARMDataOpCode.BIC;
import static cpu.ARMDataOpCode.CMN;
import static cpu.ARMDataOpCode.CMP;
import static cpu.ARMDataOpCode.EOR;
import static cpu.ARMDataOpCode.MOV;
import static cpu.ARMDataOpCode.MVN;
import static cpu.ARMDataOpCode.ORR;
import static cpu.ARMDataOpCode.RSB;
import static cpu.ARMDataOpCode.RSC;
import static cpu.ARMDataOpCode.SBC;
import static cpu.ARMDataOpCode.SUB;
import static cpu.ARMDataOpCode.TEQ;
import static cpu.ARMDataOpCode.TST;

public class ARMProcessor implements CPU.IProcessor {

	private final CPU cpu;

	public ARMProcessor(CPU cpu) {
		this.cpu = cpu;
	}

	private int getRegDelayedPC(byte reg) {
		return (reg & 0xF) == 0xF ? cpu.getPC() + 4 : cpu.getReg(reg);
	}

	private void setRegSafe(byte reg, int val) {
		if ((reg & 0xF) == 0xF)
			cpu.branch(val & 0xFFFFFFFC);
		else
			cpu.setReg(reg, val);
	}

	private void setRegSafeCPSR(byte reg, int val) {
		if ((reg & 0xF) == 0xF) {
			cpu.loadCPSR();
			val &= (cpu.cpsr.thumb) ? 0xFFFFFFFE : 0xFFFFFFFC;
			cpu.branch(val);
		}
		else
			cpu.setReg(reg, val);
	}

	/**
	 * Given the pc, accesses the cartridge ROM and retrieves the current operation bytes.
	 * If the evaluated condition is true, an operation will be decoded and executed.
	 * 
	 * @param pc Program counter for this operation
	 */
	@Override
	public void execute(int pc) {
		/*4 Bytes stored in Little-Endian format
		  31-24, 23-16 */
		byte top = cpu.accessROM(pc+3), midTop = cpu.accessROM(pc+2);
		/*15-8, 7-0*/
		byte midBot = cpu.accessROM(pc+1), bot = cpu.accessROM(pc);

		/*Top four bits of top are the condition codes
		  Byte indices start at 0, domain [0, 31]*/
		if (Condition.condition((byte) ((top >>> 4) & 0xF), cpu.cpsr)) {
			byte bit27_to_24 = (byte) (top & 0xF);
			byte bit23_to_20 = (byte) ((midTop >>> 4) & 0xF);

			switch(bit27_to_24) {
			case 0x0:
				if ((bot & 0x10) == 0 || (bot & 0x80) == 0) //Bit 4 or bit 7 clear
					dataProcessingReg(top, midTop, midBot, bot);
				else if ((bot & 0x60) == 0) { //Bit 6,5 are CLEAR
					if ((bit23_to_20 & 0xC) == 0)
						multiply(midTop, midBot, bot);
					else if ((bit23_to_20 & 0x8) == 0x8)
						multiplyLong(midTop, midBot, bot);
					else
						; //TODO Undefined
				}
				else { //Bit 6,5 are NOT both CLEAR, implies Halfword DT
					if ((bit23_to_20 & 0x4) == 0x4) //Bit 22 is SET
						halfwordDTImmediate(false, midTop, midBot, bot);
					else if ((midBot & 0xF) == 0) //Bit 22 is CLEAR AND Bit 11-8 CLEAR
						halfwordDTRegister(false, midTop, midBot, bot);
					else
						; //TODO Undefined
				}
				break;
			case 0x1:
				if (midTop == (byte)0x2F && midBot == (byte)0xFF && (bot & 0xF0) == 0x10) //0x12FFF1, Rn
					branchAndExchange((byte) (bot & 0xF));
				else if ((bot & 0x10) == 0 || (bot & 0x80) == 0) //Bit 4 or bit 7 clear
					dataProcessingReg(top, midTop, midBot, bot); 
				else if ((bot & 0x60) == 0) { //Bit 6,5 are CLEAR
					if ((bit23_to_20 & 0xB) == 0 && (midBot & 0xF) == 0) //Bit 27-25 CLEAR, Bit 24 SET, BIT 23,21,20 CLEAR, Bit 11-8 CLEAR
						singleDataSwap(midTop, midBot, bot);
					else
						; //TODO Undefined
				}
				else { //Bit 6,5 are NOT both CLEAR, implies Halfword DT
					if ((bit23_to_20 & 0x4) == 0x4) //Bit 22 is SET
						halfwordDTImmediate(true, midTop, midBot, bot);
					else if ((midBot & 0xF) == 0) //Bit 22 is CLEAR AND Bit 11-8 CLEAR
						halfwordDTRegister(true, midTop, midBot, bot);
					else
						; //TODO Undefined
				}
				break;
			case 0x2: dataProcessingImm(top, midTop, midBot, bot); break;
			case 0x3: dataProcessingImm(top, midTop, midBot, bot); break;
			case 0x4: singleDataTransferImmPost(midTop, midBot, bot); break;
			case 0x5: singleDataTransferImmPre(midTop, midBot, bot); break;
			case 0x6: 
				if ((bot & 0x10) == 0) /*Bit 4 CLEAR*/
					singleDataTransferRegPost(midTop, midBot, bot);
				else
					undefinedTrap();
				break;
			case 0x7:
				if ((bot & 0x10) == 0) /*Bit 4 CLEAR*/
					singleDataTransferRegPre(midTop, midBot, bot);
				else
					undefinedTrap();
				break;
			case 0x8: blockDataTransferPost(midTop, midBot, bot); break;
			case 0x9: blockDataTransferPre(midTop, midBot, bot); break;
			case 0xA: branch(midTop, midBot, bot); break;
			case 0xB: branchLink(midTop, midBot, bot); break;
			case 0xC: coprocDataTransferPost(midTop, midBot, bot); break;
			case 0xD: coprocDataTransferPre(midTop, midBot, bot); break;
			case 0xE:
				if ((bot & 0x10) == 0) /*Bit 4 CLEAR*/
					coprocDataOperation(midTop, midBot, bot);
				else
					coprocRegisterTransfer(midTop, midBot, bot);
				break;
			case 0xF: softwareInterrupt(midTop, midBot, bot); break;
			}

		}
	}

	private void branchAndExchange(byte rn) {
		int address = cpu.getReg(rn);
		if ((address & 0x1) == 0)
			cpu.branch(address & 0xFFFFFFFC); //Word aligned
		else {
			cpu.cpsr.thumb = true;	
			cpu.branch(address & 0xFFFFFFFE); //Halfword aligned
		}
	}

	private void dataProcessingReg(byte top, byte midTop, byte midBot, byte bot) {
		byte opcode = (byte) (((top & 0x1) << 3) | ((midTop & 0xE0) >>> 5));
		byte rd = (byte) ((midBot & 0xF0) >>> 4);
		byte shift = (byte) (((midBot & 0xF) << 4) | ((bot & 0xF0) >>> 4));
		byte rm = (byte) (bot & 0xF);
		if ((midTop & 0x10) == 0x10) //Bit 20 SET
			dataProcS(opcode, rd, getRegDelayedPC(midTop), getOp2S(shift, rm));
		else if(opcode >= TST && opcode <= CMN) //PSR Transfer
			psrTransfer(midTop, midBot, bot);
		else		
			dataProc(opcode, rd, getRegDelayedPC(midTop), getOp2(shift, rm));
	}

	private int getOp2(byte shift, byte rm) {
		byte type = (byte)((shift & 0x6) >>> 1); //type is bit 6-5
		if ((shift & 0x1) == 0) {//shift unsigned integer
			int imm5 = ((shift & 0xF8) >>> 3); //bit 11-7
			switch(type) {
			case 0: return lsli(rm, imm5);
			case 1: return lsri(rm, imm5);
			case 2: return asri(rm, imm5);
			case 3: return rori(rm, imm5);
			}
		}
		else {
			byte rs = (byte) ((shift & 0xF0) >>> 4); //rs is bit 11-8
			switch(type) {
			case 0: return lslr(rm, rs);
			case 1: return lsrr(rm, rs);
			case 2: return asrr(rm, rs);
			case 3: return rorr(rm, rs);
			}
		}
		//Should never occur
		throw new RuntimeException();
		//return 0;
	}

	private int lsli(byte rm, int imm5) {
		return cpu.getReg(rm) << imm5;
	}

	private int lsri(byte rm, int imm5) {
		return (imm5 == 0) ? 0 : cpu.getReg(rm) >>> imm5; //LSR 0 is actually LSR #32
	}

	private int asri(byte rm, int imm5) {
		if (imm5 == 0) //ASR 0 is actually ASR #32 -> same as ASR #31 value wise
			imm5 = 31; 
		return cpu.getReg(rm) >> imm5;
	}

	private int rori(byte rm, int imm5) {
		int reg = cpu.getReg(rm);
		if (imm5 > 0) //ROR
			return (reg >>> imm5) | (reg << (32 - imm5));
		else //RRX
			return ((cpu.cpsr.carry) ? 0x80000000 : 0) | (reg >>> 1);
	}

	private int lslr(byte rm, byte rs) {
		int reg = getRegDelayedPC(rm);
		int shift = getRegDelayedPC(rs) & 0xFF;
		return (shift < 32) ? reg << shift : 0;
	}

	private int lsrr(byte rm, byte rs) {
		int reg = getRegDelayedPC(rm);
		int shift = getRegDelayedPC(rs) & 0xFF;
		return (shift < 32) ? reg >>> shift : 0;
	}

	private int asrr(byte rm, byte rs) {
		int reg = getRegDelayedPC(rm);
		int shift = getRegDelayedPC(rs) & 0xFF;
		if (shift > 31)
			shift = 31;
		return reg >> shift;
	}

	private int rorr(byte rm, byte rs) {
		int reg = getRegDelayedPC(rm);
		int shift = getRegDelayedPC(rs) & 0x1F;
		return (shift > 0) ? (reg >>> shift) | (reg << (32 - shift)) : 0;
	}

	private int getOp2S(byte shift, byte rm) {
		return 0;
	}

	private void dataProcessingImm(byte top, byte midTop, byte midBot, byte bot) {
		byte opcode = (byte) (((top & 0x1) << 3) | ((midTop & 0xE0) >>> 5));
		byte rd = (byte) ((midBot & 0xF0) >>> 4);
		if ((midTop & 0x10) == 0x10) //Bit 20 SET
			dataProcS(opcode, rd, cpu.getReg(midTop), immOpS(bot & 0xFF, midBot & 0xF));
		else if(opcode >= TST && opcode <= CMN) //PSR Transfer
			psrTransferImm(midTop, midBot, bot);
		else		
			dataProc(opcode, rd, cpu.getReg(midTop), immOp(bot & 0xFF, midBot & 0xF));
	}

	private void psrTransfer(byte midTop, byte midBot, byte bot) {

	}

	private void psrTransferImm(byte midTop, byte midBot, byte bot) {

	}

	private int immOp(int val, int rotate) {
		rotate = rotate * 2; //ROR by twice the value passed in 
		if (rotate > 0) 
			val = (val >>> rotate) | (val << (32-rotate));
		return val;
	}

	private int immOpS(int val, int rotate) {
		rotate = rotate * 2; //ROR by twice the value passed in 
		if (rotate > 0) {
			cpu.cpsr.carry = (((val >>> (rotate - 1)) & 0x1) == 0x1);
			val = (val >>> rotate) | (val << (32-rotate));
		}
		return val;
	}

	private void dataProcS(byte opcode, byte rd, int op1, int op2) {
		switch(opcode) {
		case AND: ands(rd, op1, op2); break;
		case EOR: eors(rd, op1, op2); break;
		case SUB: subs(rd, op1, op2); break;
		case RSB: rsbs(rd, op1, op2); break;
		case ADD: adds(rd, op1, op2); break;
		case ADC: adcs(rd, op1, op2); break;
		case SBC: sbcs(rd, op1, op2); break;
		case RSC: rscs(rd, op1, op2); break;
		case TST: tst(rd, op1, op2); break;
		case TEQ: teq(rd, op1, op2); break;
		case CMP: cmp(rd, op1, op2); break;
		case CMN: cmn(rd, op1, op2); break;
		case ORR: orrs(rd, op1, op2); break;
		case MOV: movs(rd, op1, op2); break;
		case BIC: bics(rd, op1, op2); break;
		case MVN: mvns(rd, op1, op2); break;
		}
	}

	private void dataProc(byte opcode, byte rd, int op1, int op2) {
		switch(opcode) {
		case AND: and(rd, op1, op2); break;
		case EOR: eor(rd, op1, op2); break;
		case SUB: sub(rd, op1, op2); break;
		case RSB: rsb(rd, op1, op2); break;
		case ADD: add(rd, op1, op2); break;
		case ADC: adc(rd, op1, op2); break;
		case SBC: sbc(rd, op1, op2); break;
		case RSC: rsc(rd, op1, op2); break;
		case TST: break; //Special cases
		case TEQ: break; //Handled by PSR transfers
		case CMP: break; 
		case CMN: break;
		case ORR: orr(rd, op1, op2); break;
		case MOV: mov(rd, op1, op2); break;
		case BIC: bic(rd, op1, op2); break;
		case MVN: mvn(rd, op1, op2); break;
		}
	}

	/* OLD DATA PROC
		boolean imm = ((top & 0x2) == 0x2); //Immediate or register shift
		//Opcode is bit 24-21
		byte opcode = (byte) (((top & 0x1) << 3) | ((midTop & 0xE0) >>> 5)); 

		int op1 = (imm) ? cpu.getReg(midTop) : getRegDelayedPC(midTop); //If register shift, PC is another 4 ahead
		byte rd = (byte) ((midBot & 0xF0) >>> 4);
		int op2 = getOpS(imm, midBot, bot);

		switch(opcode) {
		case AND: ands(rd, op1, op2); break;
		case EOR: eors(rd, op1, op2); break;
		case SUB: subs(rd, op1, op2); break;
		case RSB: rsbs(rd, op1, op2); break;
		case ADD: adds(rd, op1, op2); break;
		case ADC: adcs(rd, op1, op2); break;
		case SBC: sbcs(rd, op1, op2); break;
		case RSC: rscs(rd, op1, op2); break;
		case TST: tst(rd, op1, op2); break;
		case TEQ: teq(rd, op1, op2); break;
		case CMP: cmp(rd, op1, op2); break;
		case CMN: cmn(rd, op1, op2); break;
		case ORR: orrs(rd, op1, op2); break;
		case MOV: movs(rd, op1, op2); break;
		case BIC: bics(rd, op1, op2); break;
		case MVN: mvns(rd, op1, op2); break;
		}

		private int getOpS(boolean imm, byte midBot, byte bot) {
		if (!imm) {
			byte op = (byte) ((bot & 0x60) >>> 5); //Shift is either bottom byte of register or 5 bit immediate value
			int shift = ((bot & 0x10) == 0x10) ? (cpu.getReg(midBot) & 0xFF) : (((midBot & 0xF) << 1) | ((bot & 0x80) >>> 7));
			if ((bot & 0x10) == 0 && shift == 0 && op != 0) //If LSR/ASR/ROR immediate with val = 0, val actually = 32
				shift = 32;
			int rm = getRegDelayedPC(bot);
			switch(op) {
			case 0: return lsls(rm, shift);
			case 1: return lsrs(rm, shift);
			case 2: return asrs(rm, shift);
			case 3: return rors(rm, shift);
			}
		}
		//Otherwise Rotate immediate value
		return rors(bot & 0xFF, (midBot & 0xF)*2); 
	}
	 */

	private void and(byte rd, int op1, int op2) {
		setRegSafe(rd, op1 & op2);
	}

	private void ands(byte rd, int op1, int op2) {
		int val = op1 & op2;
		cpu.cpsr.negative = (val < 0);
		cpu.cpsr.zero = (val == 0);
		setRegSafeCPSR(rd, val);
	}

	private void eor(byte rd, int op1, int op2) {
		setRegSafe(rd, op1 ^ op2);
	}

	private void eors(byte rd, int op1, int op2) {
		int val = op1 ^ op2;
		cpu.cpsr.negative = (val < 0);
		cpu.cpsr.zero = (val == 0);
		setRegSafeCPSR(rd, val);
	}

	private void sub(byte rd, int op1, int op2) {
		setRegSafe(rd, op1 - op2);
	}

	private void subs(byte rd, int op1, int op2) {
		setRegSafeCPSR(rd, cpu.setSubFlags(op1, op2));
	}

	private void rsb(byte rd, int op1, int op2) {
		setRegSafe(rd, op2 - op1);
	}

	private void rsbs(byte rd, int op1, int op2) {
		setRegSafeCPSR(rd, cpu.setSubFlags(op2, op1));
	}

	private void add(byte rd, int op1, int op2) {
		setRegSafe(rd, op1 + op2);
	}

	private void adds(byte rd, int op1, int op2) {
		setRegSafeCPSR(rd, cpu.setAddFlags(op1, op2));
	}

	private void adc(byte rd, int op1, int op2) {
		setRegSafe(rd, op1 + op2 + ((cpu.cpsr.carry) ? 1 : 0));
	}

	private void adcs(byte rd, int op1, int op2) {
		setRegSafeCPSR(rd, cpu.setAddCarryFlags(op1, op2));
	}

	private void sbc(byte rd, int op1, int op2) {
		setRegSafe(rd, op1 - op2 - ((cpu.cpsr.carry) ? 0 : 1));
	}

	private void sbcs(byte rd, int op1, int op2) {
		setRegSafeCPSR(rd, cpu.setSubCarryFlags(op1, op2));
	}

	private void rsc(byte rd, int op1, int op2) {
		setRegSafe(rd, op2 - op1 - ((cpu.cpsr.carry) ? 0 : 1));
	}

	private void rscs(byte rd, int op1, int op2) {
		setRegSafeCPSR(rd, cpu.setSubCarryFlags(op2, op1));
	}

	private void tst(byte rd, int op1, int op2) {
		int val = op1 & op2;
		cpu.cpsr.negative = (val < 0);
		cpu.cpsr.zero = (val == 0);
	}

	private void teq(byte rd, int op1, int op2) {
		int val = op1 ^ op2;
		cpu.cpsr.negative = (val < 0);
		cpu.cpsr.zero = (val == 0);
	}

	private void cmp(byte rd, int op1, int op2) {
		cpu.setSubFlags(op1, op2);
	}

	private void cmn(byte rd, int op1, int op2) {
		cpu.setAddFlags(op1, op2);
	}

	private void orr(byte rd, int op1, int op2) {
		setRegSafe(rd, op1 | op2);
	}

	private void orrs(byte rd, int op1, int op2) {
		int val = op1 | op2;
		cpu.cpsr.negative = (val < 0);
		cpu.cpsr.zero = (val == 0);
		setRegSafeCPSR(rd, val);
	}

	private void mov(byte rd, int op1, int op2) {
		setRegSafe(rd, op2);
	}

	private void movs(byte rd, int op1, int op2) {
		cpu.cpsr.negative = (op2 < 0);
		cpu.cpsr.zero = (op2 == 0);
		setRegSafeCPSR(rd, op2);
	}

	private void bic(byte rd, int op1, int op2) {
		setRegSafe(rd, op1 & ~op2);
	}

	private void bics(byte rd, int op1, int op2) {
		int val = op1 & ~op2;
		cpu.cpsr.negative = (val < 0);
		cpu.cpsr.zero = (val == 0);
		setRegSafeCPSR(rd, val);
	}

	private void mvn(byte rd, int op1, int op2) {
		setRegSafe(rd, ~op2);
	}

	private void mvns(byte rd, int op1, int op2) {
		op2 = ~op2;
		cpu.cpsr.negative = (op2 < 0);
		cpu.cpsr.zero = (op2 == 0);
		setRegSafeCPSR(rd, op2);
	}

	private void multiply(byte midTop, byte midBot, byte bot) {

	}

	private void multiplyLong(byte midTop, byte midBot, byte bot) {

	}

	private void singleDataSwap(byte midTop, byte midBot, byte bot) {

	}

	private void halfwordDTImmediate(boolean p, byte midTop, byte midBot, byte bot) {

	}

	private void halfwordDTRegister(boolean p, byte midTop, byte midBot, byte bot) {

	}

	private void singleDataTransferImmPre(byte midTop, byte midBot, byte bot) {

	}

	private void singleDataTransferImmPost(byte midTop, byte midBot, byte bot) {

	}

	private void singleDataTransferRegPre(byte midTop, byte midBot, byte bot) {

	}

	private void singleDataTransferRegPost(byte midTop, byte midBot, byte bot) {

	}

	private void undefinedTrap() {

	}

	private void blockDataTransferPre(byte midTop, byte midBot, byte bot) {

	}

	private void blockDataTransferPost(byte midTop, byte midBot, byte bot) {

	}

	private void branchLink(byte midTop, byte midBot, byte bot) {

	}

	private void branch(byte midTop, byte midBot, byte bot) {

	}

	private void coprocDataTransferPre(byte midTop, byte midBot, byte bot) {

	}

	private void coprocDataTransferPost(byte midTop, byte midBot, byte bot) {

	}

	private void coprocDataOperation(byte midTop, byte midBot, byte bot) {

	}

	private void coprocRegisterTransfer(byte midTop, byte midBot, byte bot) {

	}

	private void softwareInterrupt(byte midTop, byte midBot, byte bot) {

	}

}
