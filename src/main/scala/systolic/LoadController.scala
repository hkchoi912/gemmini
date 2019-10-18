package systolic

import chisel3._
import chisel3.util._
import SystolicISA._
import Util._
import freechips.rocketchip.config.Parameters

// TODO deal with errors when reading scratchpad responses
// class LoadController[T <: Data](config: SystolicArrayConfig[T], coreMaxAddrBits: Int, sp_addr_t: SPAddr, acc_addr_t: AccAddr)
class LoadController[T <: Data](config: SystolicArrayConfig[T], coreMaxAddrBits: Int, local_addr_t: LocalAddr)
                               (implicit p: Parameters) extends Module {
  import config._

  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new SystolicCmdWithDeps))

    // val dma = new ScratchpadReadMemIO(sp_banks, sp_bank_entries, acc_rows)
    val dma = new ScratchpadReadMemIO(local_addr_t)

    // TODO what's a better way to express no bits?
    val pushStore = Decoupled(UInt(1.W))
    val pullStore = Flipped(Decoupled(UInt(1.W)))
    val pushEx = Decoupled(UInt(1.W))
    val pullEx = Flipped(Decoupled(UInt(1.W)))

    val busy = Output(Bool())
  })

  val waiting_for_command :: waiting_for_dma_req_ready :: sending_rows :: Nil = Enum(3)
  val control_state = RegInit(waiting_for_command)

  val stride = RegInit((sp_width / 8).U(coreMaxAddrBits.W))
  val block_rows = meshRows * tileRows
  val block_cols = meshColumns * tileColumns
  val row_counter = RegInit(0.U(log2Ceil(block_rows).W))

  val cmd = Queue(io.cmd, ld_str_queue_length)
  val vaddr = cmd.bits.cmd.rs1
  val localaddr = cmd.bits.cmd.rs2.asTypeOf(local_addr_t)
  val len = cmd.bits.cmd.rs2(coreMaxAddrBits-1, 32) // TODO we don't really need to read all the bits here
  val config_stride = cmd.bits.cmd.rs2
  val mstatus = cmd.bits.cmd.status

  val localaddr_plus_row_counter = localaddr + row_counter

  io.busy := cmd.valid

  val DoConfig = cmd.bits.cmd.inst.funct === CONFIG_CMD
  val DoLoad = !DoConfig // TODO change this if more commands are added

  cmd.ready := false.B

  val pullEx = cmd.bits.deps.pullEx
  val pushEx = cmd.bits.deps.pushEx
  val pullStore = cmd.bits.deps.pullStore
  val pushStore = cmd.bits.deps.pushStore
  val pullDep = pullEx || pullStore
  val pushDep = pushEx || pushStore

  val pull_deps_ready = !pullDep || (pullEx && io.pullEx.valid && !pullStore) ||
    (pullStore && io.pullStore.valid && !pullEx) || (pullEx && pullStore && io.pullEx.valid && io.pullStore.valid)
  val push_deps_ready = !pushDep || (pushEx && io.pushEx.ready && !pushStore) ||
    (pushStore && io.pushStore.ready && !pushEx) || (pushEx && pushStore && io.pushEx.ready && io.pushStore.ready)

  io.dma.req.valid := (control_state === waiting_for_command && cmd.valid && DoLoad && pull_deps_ready) ||
    control_state === waiting_for_dma_req_ready ||
    (control_state === sending_rows && row_counter =/= 0.U)
  io.dma.req.bits.vaddr := vaddr + row_counter * stride
  io.dma.req.bits.laddr := localaddr_plus_row_counter
  io.dma.req.bits.len := len
  io.dma.req.bits.status := mstatus

  io.pushStore.valid :=  false.B
  io.pushEx.valid := false.B
  io.pullStore.ready := false.B
  io.pullEx.ready := false.B

  // TODO are these really needed?
  io.pushStore.bits := DontCare
  io.pushEx.bits := DontCare

  // Command tracker
  val deps_t = new Bundle {
    val pushStore = Bool()
    val pushEx = Bool()
  }

  val nCmds = 2 // TODO make this a config parameter

  val maxBytesInRowRequest = config.dma_maxbytes max (block_cols * config.inputType.getWidth / 8) max
    (block_cols * config.accType.getWidth / 8)
  val maxBytesInMatRequest = block_rows * maxBytesInRowRequest

  // TODO we don't actually check that the instructions in the cmd_tracker push dependencies in order
  val cmd_tracker = Module(new DMAReadCommandTracker(nCmds, maxBytesInMatRequest, deps_t))
  cmd_tracker.io.alloc.valid := control_state === waiting_for_command && cmd.valid && DoLoad && pull_deps_ready
  cmd_tracker.io.alloc.bits.bytes_to_read := len * block_rows.U * // TODO change len to lgLen so that the multiplier here can be removed
    block_cols.U * (Mux(localaddr.is_acc_addr, config.accType.getWidth.U, config.inputType.getWidth.U) / 8.U)
  cmd_tracker.io.alloc.bits.tag.pushStore := pushStore
  cmd_tracker.io.alloc.bits.tag.pushEx := pushEx
  cmd_tracker.io.request_returned.valid := io.dma.resp.fire() // TODO use a bundle connect
  cmd_tracker.io.request_returned.bits.cmd_id := io.dma.resp.bits.cmd_id // TODO use a bundle connect
  cmd_tracker.io.request_returned.bits.bytes_read := io.dma.resp.bits.bytesRead
  cmd_tracker.io.cmd_completed.ready :=
    !(cmd_tracker.io.cmd_completed.bits.tag.pushStore && !io.pushStore.ready) &&
      !(cmd_tracker.io.cmd_completed.bits.tag.pushEx && !io.pushEx.ready)

  val cmd_id = RegEnableThru(cmd_tracker.io.alloc.bits.cmd_id, cmd_tracker.io.alloc.fire()) // TODO is this really better than a simple RegEnable?
  io.dma.req.bits.cmd_id := cmd_id

  when (cmd_tracker.io.cmd_completed.fire()) {
    val tag = cmd_tracker.io.cmd_completed.bits.tag
    io.pushStore.valid := tag.pushStore
    io.pushEx.valid := tag.pushEx
  }

  // Row counter
  when (io.dma.req.fire()) {
    row_counter := wrappingAdd(row_counter, 1.U, block_rows)
  }

  // Control logic
  switch (control_state) {
    is (waiting_for_command) {
      when (cmd.valid && pull_deps_ready) {
        when(DoConfig && push_deps_ready && !cmd_tracker.io.busy) {
          stride := config_stride

          io.pushStore.valid := pushStore
          io.pullStore.ready := pullStore
          io.pullEx.ready := pullEx
          io.pushEx.valid := pushEx

          cmd.ready := true.B
        }

        .elsewhen(DoLoad && cmd_tracker.io.alloc.fire()) {
          io.pullEx.ready := pullEx
          io.pullStore.ready := pullStore
          control_state := Mux(io.dma.req.fire(), sending_rows, waiting_for_dma_req_ready)
        }
      }
    }

    is (waiting_for_dma_req_ready) {
      when (io.dma.req.fire()) {
        control_state := sending_rows
      }
    }

    is (sending_rows) {
      val last_row = row_counter === 0.U || (row_counter === (block_rows-1).U && io.dma.req.fire())

      when (last_row) {
        control_state := waiting_for_command
        cmd.ready := true.B
      }
    }
  }
}
