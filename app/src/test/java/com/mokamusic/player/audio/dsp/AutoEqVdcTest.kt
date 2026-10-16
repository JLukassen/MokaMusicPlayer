package com.mokamusic.player.audio.dsp
import org.junit.Assert.*
import org.junit.Test
class AutoEqVdcTest {
 @Test fun searchIndex() {
  val index="- [Sennheiser HD 650](./oratory1990/over-ear/Sennheiser%20HD%20650) by oratory1990"
  assertEquals("Sennheiser HD 650",AutoEqCatalog.parseIndex(index,"HD 650").single().model)
 }
 @Test fun convertParametricToVdc() {
  val vdc=AutoEqVdc.convert("Preamp: -6.1 dB\nFilter 1: ON PK Fc 120 Hz Gain 5.0 dB Q 0.70")
  val p=VdcParser.parse(vdc)
  assertEquals(6,p.bySampleRate.size)
  assertEquals(2,p.sectionsFor(48000)?.size)
 }
}