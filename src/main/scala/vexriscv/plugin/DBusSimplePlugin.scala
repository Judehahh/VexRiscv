package vexriscv.plugin

import vexriscv._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.avalon.{AvalonMM, AvalonMMConfig}
import spinal.lib.bus.wishbone.{Wishbone, WishboneConfig}
import spinal.lib.bus.simple._
import vexriscv.ip.DataCacheMemCmd

import scala.collection.mutable.ArrayBuffer


case class DBusSimpleCmd() extends Bundle{
  val wr = Bool
  val address = UInt(32 bits)
  val data = Bits(32 bit)
  val size = UInt(2 bit)
}

case class DBusSimpleRsp() extends Bundle with IMasterSlave{
  val ready = Bool
  val error = Bool
  val data = Bits(32 bit)

  override def asMaster(): Unit = {
    out(ready,error,data)
  }
}


object DBusSimpleBus{
  def getAxi4Config() = Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    useId = false,
    useRegion = false,
    useBurst = false,
    useLock = false,
    useQos = false,
    useLen = false,
    useResp = true
  )

  def getAvalonConfig() = AvalonMMConfig.pipelined(
    addressWidth = 32,
    dataWidth = 32).copy(
    useByteEnable = true,
    useResponse = true,
    maximumPendingReadTransactions = 1
  )

  def getWishboneConfig() = WishboneConfig(
    addressWidth = 30,
    dataWidth = 32,
    selWidth = 4,
    useSTALL = false,
    useLOCK = false,
    useERR = true,
    useRTY = false,
    tgaWidth = 0,
    tgcWidth = 0,
    tgdWidth = 0,
    useBTE = true,
    useCTI = true
  )
}

case class DBusSimpleBus() extends Bundle with IMasterSlave{
  val cmd = Stream(DBusSimpleCmd())
  val rsp = DBusSimpleRsp()

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
  }

  def toAxi4Shared(stageCmd : Boolean = true): Axi4Shared = {
    val axi = Axi4Shared(DBusSimpleBus.getAxi4Config())
    val pendingWritesMax = 7
    val pendingWrites = CounterUpDown(
      stateCount = pendingWritesMax + 1,
      incWhen = axi.sharedCmd.fire && axi.sharedCmd.write,
      decWhen = axi.writeRsp.fire
    )

    val cmdPreFork = if (stageCmd) cmd.stage.stage().s2mPipe() else cmd
    val (cmdFork, dataFork) = StreamFork2(cmdPreFork.haltWhen((pendingWrites =/= 0 && !cmdPreFork.wr) || pendingWrites === pendingWritesMax))
    axi.sharedCmd.arbitrationFrom(cmdFork)
    axi.sharedCmd.write := cmdFork.wr
    axi.sharedCmd.prot := "010"
    axi.sharedCmd.cache := "1111"
    axi.sharedCmd.size := cmdFork.size.resized
    axi.sharedCmd.addr := cmdFork.address

    val dataStage = dataFork.throwWhen(!dataFork.wr)
    axi.writeData.arbitrationFrom(dataStage)
    axi.writeData.last := True
    axi.writeData.data := dataStage.data
    axi.writeData.strb := (dataStage.size.mux(
      U(0) -> B"0001",
      U(1) -> B"0011",
      default -> B"1111"
    ) << dataStage.address(1 downto 0)).resized


    rsp.ready := axi.r.valid
    rsp.error := !axi.r.isOKAY()
    rsp.data := axi.r.data

    axi.r.ready := True
    axi.b.ready := True


    //TODO remove
    val axi2 = Axi4Shared(DBusSimpleBus.getAxi4Config())
    axi.arw >-> axi2.arw
    axi.w >> axi2.w
    axi.r << axi2.r
    axi.b << axi2.b
//    axi2 << axi
    axi2
  }

  def toAxi4(stageCmd : Boolean = true) = this.toAxi4Shared(stageCmd).toAxi4()



  def toAvalon(stageCmd : Boolean = true): AvalonMM = {
    val avalonConfig = DBusSimpleBus.getAvalonConfig()
    val mm = AvalonMM(avalonConfig)
    val cmdStage = if(stageCmd) cmd.stage else cmd
    mm.read := cmdStage.valid && !cmdStage.wr
    mm.write := cmdStage.valid && cmdStage.wr
    mm.address := (cmdStage.address >> 2) @@ U"00"
    mm.writeData := cmdStage.data(31 downto 0)
    mm.byteEnable := (cmdStage.size.mux (
      U(0) -> B"0001",
      U(1) -> B"0011",
      default -> B"1111"
    ) << cmdStage.address(1 downto 0)).resized


    cmdStage.ready := mm.waitRequestn
    rsp.ready :=mm.readDataValid
    rsp.error := mm.response =/= AvalonMM.Response.OKAY
    rsp.data := mm.readData

    mm
  }

  def toWishbone(): Wishbone = {
    val wishboneConfig = DBusSimpleBus.getWishboneConfig()
    val bus = Wishbone(wishboneConfig)
    val cmdStage = cmd.halfPipe()

    bus.ADR := cmdStage.address >> 2
    bus.CTI :=B"000"
    bus.BTE := "00"
    bus.SEL := (cmdStage.size.mux (
      U(0) -> B"0001",
      U(1) -> B"0011",
      default -> B"1111"
    ) << cmdStage.address(1 downto 0)).resized
    when(!cmdStage.wr) {
      bus.SEL := "1111"
    }
    bus.WE  := cmdStage.wr
    bus.DAT_MOSI := cmdStage.data

    cmdStage.ready := cmdStage.valid && bus.ACK
    bus.CYC := cmdStage.valid
    bus.STB := cmdStage.valid

    rsp.ready := cmdStage.valid && !bus.WE && bus.ACK
    rsp.data  := bus.DAT_MISO
    rsp.error := False //TODO
    bus
  }

  def toPipelinedMemoryBus() : PipelinedMemoryBus = {
    val bus = PipelinedMemoryBus(32,32)
    bus.cmd.valid := cmd.valid
    bus.cmd.write := cmd.wr
    bus.cmd.address := cmd.address.resized
    bus.cmd.data := cmd.data
    bus.cmd.mask := cmd.size.mux(
      0 -> B"0001",
      1 -> B"0011",
      default -> B"1111"
    ) |<< cmd.address(1 downto 0)
    cmd.ready := bus.cmd.ready

    rsp.ready := bus.rsp.valid
    rsp.data := bus.rsp.data

    bus
  }
}


class DBusSimplePlugin(catchAddressMisaligned : Boolean = false,
                       catchAccessFault : Boolean = false,
                       earlyInjection : Boolean = false, /*, idempotentRegions : (UInt) => Bool = (x) => False*/
                       emitCmdInMemoryStage : Boolean = false,
                       onlyLoadWords : Boolean = false,
                       atomicEntriesCount : Int = 0,
                       memoryTranslatorPortConfig : Any = null) extends Plugin[VexRiscv] with DBusAccessService {

  var dBus  : DBusSimpleBus = null
  assert(!(emitCmdInMemoryStage && earlyInjection))
  def genAtomic = atomicEntriesCount != 0
  object MEMORY_ENABLE extends Stageable(Bool)
  object MEMORY_READ_DATA extends Stageable(Bits(32 bits))
  object MEMORY_ADDRESS_LOW extends Stageable(UInt(2 bits))
  object ALIGNEMENT_FAULT extends Stageable(Bool)
  object MMU_FAULT extends Stageable(Bool)
  object MMU_RSP extends Stageable(MemoryTranslatorRsp())
  object MEMORY_ATOMIC extends Stageable(Bool)
  object ATOMIC_HIT extends Stageable(Bool)
  object MEMORY_STORE extends Stageable(Bool)

  var memoryExceptionPort : Flow[ExceptionCause] = null
  var rspStage : Stage = null
  var mmuBus : MemoryTranslatorBus = null
  var redoBranch : Flow[UInt] = null
  val catchSomething = catchAccessFault || catchAddressMisaligned || memoryTranslatorPortConfig != null

  @dontName var dBusAccess : DBusAccess = null
  override def newDBusAccess(): DBusAccess = {
    assert(dBusAccess == null)
    dBusAccess = DBusAccess()
    dBusAccess
  }

  override def setup(pipeline: VexRiscv): Unit = {
    import Riscv._
    import pipeline.config._
    import pipeline._

    val decoderService = pipeline.service(classOf[DecoderService])

    val stdActions = List[(Stageable[_ <: BaseType],Any)](
      SRC1_CTRL         -> Src1CtrlEnum.RS,
      SRC_USE_SUB_LESS  -> False,
      MEMORY_ENABLE     -> True,
      RS1_USE          -> True
    ) ++ (if(catchAccessFault || catchAddressMisaligned) List(IntAluPlugin.ALU_CTRL -> IntAluPlugin.AluCtrlEnum.ADD_SUB) else Nil) //Used for access fault bad address in memory stage

    val loadActions = stdActions ++ List(
      SRC2_CTRL -> Src2CtrlEnum.IMI,
      REGFILE_WRITE_VALID -> True,
      BYPASSABLE_EXECUTE_STAGE -> False,
      BYPASSABLE_MEMORY_STAGE  -> Bool(earlyInjection),
      MEMORY_STORE -> False
    ) ++ (if(catchAccessFault || catchAddressMisaligned) List(HAS_SIDE_EFFECT -> True) else Nil)

    val storeActions = stdActions ++ List(
      SRC2_CTRL -> Src2CtrlEnum.IMS,
      RS2_USE -> True,
      MEMORY_STORE -> True
    )

    decoderService.addDefault(MEMORY_ENABLE, False)
    decoderService.add(
      (if(onlyLoadWords) List(LW) else List(LB, LH, LW, LBU, LHU, LWU)).map(_ -> loadActions) ++
      List(SB, SH, SW).map(_ -> storeActions)
    )


    if(genAtomic){
      List(LB, LH, LW, LBU, LHU, LWU, SB, SH, SW).foreach(e =>
        decoderService.add(e, Seq(MEMORY_ATOMIC -> False))
      )
      decoderService.add(
        key = LR,
        values = loadActions.filter(_._1 != SRC2_CTRL) ++ Seq(
          SRC2_CTRL -> Src2CtrlEnum.RS,
          MEMORY_ATOMIC -> True
        )
      )
      //TODO probably the cached implemention of SC is bugy (address calculation)
      decoderService.add(
        key = SC,
        values = storeActions.filter(_._1 != SRC2_CTRL) ++ Seq(
          REGFILE_WRITE_VALID -> True,
          BYPASSABLE_EXECUTE_STAGE -> False,
          BYPASSABLE_MEMORY_STAGE -> False,
          MEMORY_ATOMIC -> True
        )
      )
    }


    rspStage = if(stages.last == execute) execute else (if(emitCmdInMemoryStage) writeBack else memory)
    if(catchSomething) {
      val exceptionService = pipeline.service(classOf[ExceptionService])
      memoryExceptionPort = exceptionService.newExceptionPort(rspStage)
    }

    if(memoryTranslatorPortConfig != null) {
      mmuBus = pipeline.service(classOf[MemoryTranslator]).newTranslationPort(MemoryTranslatorPort.PRIORITY_DATA, memoryTranslatorPortConfig)
      redoBranch = pipeline.service(classOf[JumpService]).createJumpInterface(pipeline.memory)
    }
  }

  override def build(pipeline: VexRiscv): Unit = {
    import pipeline._
    import pipeline.config._

    dBus = master(DBusSimpleBus()).setName("dBus")

    //Emit dBus.cmd request
    val cmdStage = if(emitCmdInMemoryStage) memory else execute
    cmdStage plug new Area{
      import cmdStage._
      val privilegeService = pipeline.serviceElse(classOf[PrivilegeService], PrivilegeServiceDefault())

      val cmdSent =  if(rspStage == execute) RegInit(False) setWhen(dBus.cmd.fire) clearWhen(!execute.arbitration.isStuck) else False

      if (catchAddressMisaligned)
        insert(ALIGNEMENT_FAULT) := (dBus.cmd.size === 2 && dBus.cmd.address(1 downto 0) =/= 0) || (dBus.cmd.size === 1 && dBus.cmd.address(0 downto 0) =/= 0)
      else
        insert(ALIGNEMENT_FAULT) := False


      val skipCmd = False
      skipCmd setWhen(input(ALIGNEMENT_FAULT))

      dBus.cmd.valid := arbitration.isValid && input(MEMORY_ENABLE) && !arbitration.isStuckByOthers && !arbitration.isFlushed && !skipCmd && !cmdSent
      dBus.cmd.wr := input(MEMORY_STORE)
      dBus.cmd.size := input(INSTRUCTION)(13 downto 12).asUInt
      dBus.cmd.payload.data := dBus.cmd.size.mux (
        U(0) -> input(RS2)(7 downto 0) ## input(RS2)(7 downto 0) ## input(RS2)(7 downto 0) ## input(RS2)(7 downto 0),
        U(1) -> input(RS2)(15 downto 0) ## input(RS2)(15 downto 0),
        default -> input(RS2)(31 downto 0)
      )
      when(arbitration.isValid && input(MEMORY_ENABLE) && !dBus.cmd.ready && !skipCmd && !cmdSent){
        arbitration.haltItself := True
      }

      insert(MEMORY_ADDRESS_LOW) := dBus.cmd.address(1 downto 0)

      //formal
      val formalMask = dBus.cmd.size.mux(
        U(0) -> B"0001",
        U(1) -> B"0011",
        default -> B"1111"
      ) |<< dBus.cmd.address(1 downto 0)

      insert(FORMAL_MEM_ADDR) := dBus.cmd.address & U"xFFFFFFFC"
      insert(FORMAL_MEM_WMASK) := (dBus.cmd.valid &&  dBus.cmd.wr) ? formalMask | B"0000"
      insert(FORMAL_MEM_RMASK) := (dBus.cmd.valid && !dBus.cmd.wr) ? formalMask | B"0000"
      insert(FORMAL_MEM_WDATA) := dBus.cmd.payload.data

      val mmu = (mmuBus != null) generate new Area {
        mmuBus.cmd.isValid := arbitration.isValid && input(MEMORY_ENABLE)
        mmuBus.cmd.virtualAddress := input(SRC_ADD).asUInt
        mmuBus.cmd.bypassTranslation := False
        dBus.cmd.address := mmuBus.rsp.physicalAddress

        //do not emit memory request if MMU refilling
        insert(MMU_FAULT) := input(MMU_RSP).exception || (!input(MMU_RSP).allowWrite && input(MEMORY_STORE)) || (!input(MMU_RSP).allowRead && !input(MEMORY_STORE)) || (!input(MMU_RSP).allowUser && privilegeService.isUser())
        skipCmd.setWhen(input(MMU_FAULT) || input(MMU_RSP).refilling)

        insert(MMU_RSP) := mmuBus.rsp
      }

      val mmuLess = (mmuBus == null) generate new Area{
        dBus.cmd.address := input(SRC_ADD).asUInt
      }


      val atomic = genAtomic generate new Area{
        val address = input(SRC1).asUInt //TODO could avoid 32 muxes if SRC_ADD can be disabled
        case class AtomicEntry() extends Bundle{
          val valid = Bool()
          val address = UInt(32 bits)

          def init: this.type ={
            valid init(False)
            this
          }
        }
        val entries = Vec(Reg(AtomicEntry()).init, atomicEntriesCount)
        val entriesAllocCounter = Counter(atomicEntriesCount)
        insert(ATOMIC_HIT) := entries.map(e => e.valid && e.address === address).orR
        when(arbitration.isValid &&  input(MEMORY_ENABLE) && input(MEMORY_ATOMIC) && !input(MEMORY_STORE)){
          entries(entriesAllocCounter).valid := True
          entries(entriesAllocCounter).address := address
          when(!arbitration.isStuck){
            entriesAllocCounter.increment()
          }
        }
        when(service(classOf[IContextSwitching]).isContextSwitching){
          entries.foreach(_.valid := False)
        }

        when(input(MEMORY_STORE) && input(MEMORY_ATOMIC) && !input(ATOMIC_HIT)){
          skipCmd := True
        }
        when(input(MEMORY_ATOMIC)){
          mmuBus.cmd.virtualAddress := address
        }
      }
    }

    //Collect dBus.rsp read responses
    rspStage plug new Area {
      val s = rspStage; import s._


      insert(MEMORY_READ_DATA) := dBus.rsp.data

      arbitration.haltItself setWhen(arbitration.isValid && input(MEMORY_ENABLE) && !input(MEMORY_STORE) && !dBus.rsp.ready)

      if(catchSomething) {
        memoryExceptionPort.valid := False
        memoryExceptionPort.code.assignDontCare()
        memoryExceptionPort.badAddr := input(REGFILE_WRITE_DATA).asUInt

        if(catchAccessFault) when(dBus.rsp.ready && dBus.rsp.error && !input(MEMORY_STORE)) {
          memoryExceptionPort.valid := True
          memoryExceptionPort.code := 5
        }

        if(catchAddressMisaligned) when(input(ALIGNEMENT_FAULT)){
          memoryExceptionPort.code := (input(MEMORY_STORE) ? U(6) | U(4)).resized
          memoryExceptionPort.valid := True
        }

        if(memoryTranslatorPortConfig != null) {
          redoBranch.valid := False
          redoBranch.payload := input(PC)

          when(input(MMU_RSP).refilling){
            redoBranch.valid := True
            memoryExceptionPort.valid := False
          } elsewhen(input(MMU_FAULT)) {
            memoryExceptionPort.valid := True
            memoryExceptionPort.code := (input(MEMORY_STORE) ? U(15) | U(13)).resized
          }

          arbitration.flushAll setWhen(redoBranch.valid)
        }

        when(!(arbitration.isValid && input(MEMORY_ENABLE) && (Bool(cmdStage != rspStage) || !arbitration.isStuckByOthers))){
          if(catchSomething) memoryExceptionPort.valid := False
          if(memoryTranslatorPortConfig != null) redoBranch.valid := False
        }

      }


      if(rspStage != execute) assert(!(dBus.rsp.ready && input(MEMORY_ENABLE) && arbitration.isValid && arbitration.isStuck),"DBusSimplePlugin doesn't allow memory stage stall when read happend")
    }

    //Reformat read responses, REGFILE_WRITE_DATA overriding
    val injectionStage = if(earlyInjection) memory else stages.last
    injectionStage plug new Area {
      import injectionStage._


      val rspShifted = MEMORY_READ_DATA()
      rspShifted := input(MEMORY_READ_DATA)
      switch(input(MEMORY_ADDRESS_LOW)){
        is(1){rspShifted(7 downto 0) := input(MEMORY_READ_DATA)(15 downto 8)}
        is(2){rspShifted(15 downto 0) := input(MEMORY_READ_DATA)(31 downto 16)}
        is(3){rspShifted(7 downto 0) := input(MEMORY_READ_DATA)(31 downto 24)}
      }

      val rspFormated = input(INSTRUCTION)(13 downto 12).mux(
        0 -> B((31 downto 8) -> (rspShifted(7) && !input(INSTRUCTION)(14)),(7 downto 0) -> rspShifted(7 downto 0)),
        1 -> B((31 downto 16) -> (rspShifted(15) && ! input(INSTRUCTION)(14)),(15 downto 0) -> rspShifted(15 downto 0)),
        default -> rspShifted //W
      )

      when(arbitration.isValid && input(MEMORY_ENABLE)) {
        output(REGFILE_WRITE_DATA) := (if(!onlyLoadWords) rspFormated else input(MEMORY_READ_DATA))
        if(genAtomic){
          when(input(MEMORY_ATOMIC) && input(MEMORY_STORE)){
            output(REGFILE_WRITE_DATA)  := (!input(ATOMIC_HIT)).asBits.resized
          }
        }
      }

      if(!earlyInjection && !emitCmdInMemoryStage && config.withWriteBackStage)
        assert(!(arbitration.isValid && input(MEMORY_ENABLE) && !input(MEMORY_STORE) && arbitration.isStuck),"DBusSimplePlugin doesn't allow writeback stage stall when read happend")

      //formal
      insert(FORMAL_MEM_RDATA) := input(MEMORY_READ_DATA)
    }

    //Share access to the dBus (used by self refilled MMU)
    val dBusSharing = (dBusAccess != null) generate new Area{
      val state = Reg(UInt(2 bits)) init(0)
      dBusAccess.cmd.ready := False
      dBusAccess.rsp.valid := False
      dBusAccess.rsp.data := dBus.rsp.data
      dBusAccess.rsp.error := dBus.rsp.error

      switch(state){
        is(0){
          when(dBusAccess.cmd.valid){
            decode.arbitration.haltItself := True
            when(!stages.dropWhile(_ != execute).map(_.arbitration.isValid).orR){
              state := 1
            }
          }
        }
        is(1){
          decode.arbitration.haltItself := True
          dBus.cmd.valid := True
          dBus.cmd.address := dBusAccess.cmd.address
          dBus.cmd.wr := dBusAccess.cmd.write
          dBus.cmd.data := dBusAccess.cmd.data
          dBus.cmd.size := dBusAccess.cmd.size
          when(dBus.cmd.ready){
            state := (dBusAccess.cmd.write ? U(0) | U(2))
            dBusAccess.cmd.ready := True
          }
        }
        is(2){
          decode.arbitration.haltItself := True
          when(dBus.rsp.ready){
            dBusAccess.rsp.valid := True
            state := 0
          }
        }
      }
    }
  }
}
