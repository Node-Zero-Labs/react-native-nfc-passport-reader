package com.nfcpassportreader

import android.content.Context
import android.nfc.tech.IsoDep
import com.nfcpassportreader.utils.*
import com.nfcpassportreader.dto.*
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.iso19794.FaceImageInfo
import java.io.ByteArrayInputStream

class NfcPassportReader(context: Context) {
  private val bitmapUtil = BitmapUtil(context)
  private val dateUtil = DateUtil()

  fun readPassport(isoDep: IsoDep, bacKey: BACKeySpec, includeImages: Boolean): NfcResult {
    isoDep.timeout = 10000

    val cardService = CardService.getInstance(isoDep)
    cardService.open()

    try {
      val service = PassportService(
        cardService,
        PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
        PassportService.DEFAULT_MAX_BLOCKSIZE,
        false,
        false
      )
      service.open()

      var paceSucceeded = false
      try {
        // EF.CardAccess (unlike EF.CardSecurity) is readable pre-authentication and is
        // an unsigned SET OF SecurityInfo (CardAccessFile), not the CMS-signed format
        // CardSecurityFile parses. Passports that enforce PACE-only reject BAC's MUTUAL
        // AUTHENTICATE (SW=0x6985) if PACE discovery here is skipped or fails silently.
        val cardAccessFile =
          CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS))
        val securityInfoCollection = cardAccessFile.securityInfos

        for (securityInfo in securityInfoCollection) {
          if (securityInfo is PACEInfo) {
            service.doPACE(
              bacKey,
              securityInfo.objectIdentifier,
              PACEInfo.toParameterSpec(securityInfo.parameterId),
              null
            )
            paceSucceeded = true
          }
        }
      } catch (e: Exception) {
        e.printStackTrace()
      }

      service.sendSelectApplet(paceSucceeded)

      if (!paceSucceeded) {
        try {
          service.getInputStream(PassportService.EF_COM).read()
        } catch (e: Exception) {
          e.printStackTrace()

          service.doBAC(bacKey)
        }
      }

      val nfcResult = NfcResult()

      // Captured before parsing so the returned hex is the exact chip bytes (needed for
      // downstream SOD hash/signature verification), not JMRTD's re-encoded representation.
      val dg1Bytes = service.getInputStream(PassportService.EF_DG1).readBytes()
      val dg1File = DG1File(ByteArrayInputStream(dg1Bytes))
      val mrzInfo = dg1File.mrzInfo
      nfcResult.dg1Hex = dg1Bytes.toHexString()

      val sodBytes = service.getInputStream(PassportService.EF_SOD).readBytes()
      nfcResult.sodHex = sodBytes.toHexString()

      // DG11 (Additional Personal Details) is optional per ICAO 9303 - many passports
      // (e.g. Swedish ones) omit it entirely, which SELECTs 6A82 FILE NOT FOUND. Fall
      // back to the mandatory DG1 MRZ fields for name/birth date when it's absent.
      val dg11File = try {
        DG11File(service.getInputStream(PassportService.EF_DG11))
      } catch (e: Exception) {
        null
      }

      if (!dg11File?.nameOfHolder.isNullOrEmpty()) {
        val name = dg11File!!.nameOfHolder.substringAfterLast("<<").replace("<", " ")
        val surname = dg11File.nameOfHolder.substringBeforeLast("<<")
        nfcResult.firstName = name
        nfcResult.lastName = surname
      } else {
        nfcResult.firstName = mrzInfo.secondaryIdentifier.replace("<", " ").trim()
        nfcResult.lastName = mrzInfo.primaryIdentifier.replace("<", " ").trim()
      }

      if(!dg11File?.placeOfBirth.isNullOrEmpty()){
        nfcResult.placeOfBirth = dg11File!!.placeOfBirth.joinToString(separator = " ")
      }

      if(!dg11File?.fullDateOfBirth.isNullOrEmpty()){
        nfcResult.birthDate = dateUtil.convertFromMrzDate(dg11File!!.fullDateOfBirth)
      } else if (!mrzInfo.dateOfBirth.isNullOrEmpty()) {
        nfcResult.birthDate = dateUtil.convertFromMrzDate(mrzInfo.dateOfBirth)
      }

      mrzInfo.let {
        if(!it.dateOfExpiry.isNullOrEmpty()){
          nfcResult.expiryDate = dateUtil.convertFromMrzDate(it.dateOfExpiry)
        }

        nfcResult.identityNo = mrzInfo.personalNumber
        nfcResult.gender = mrzInfo.gender.toString()
        nfcResult.documentNo = it.documentNumber
        nfcResult.nationality = it.nationality
        nfcResult.mrz = it.toString()
      }

      if (includeImages) {
        val dg2In = service.getInputStream(PassportService.EF_DG2)
        val dg2File = DG2File(dg2In)
        val faceInfos = dg2File.faceInfos
        val allFaceImageInfos: MutableList<FaceImageInfo> = ArrayList()
        for (faceInfo in faceInfos) {
          allFaceImageInfos.addAll(faceInfo.faceImageInfos)
        }
        if (allFaceImageInfos.isNotEmpty()) {
          val faceImageInfo = allFaceImageInfos.iterator().next()
          val image = bitmapUtil.getImage(faceImageInfo)
          nfcResult.originalFacePhoto = image
        }
      }

      return nfcResult
    } finally {
      // A dangling, unclosed connection after a failed/retried read can delay or block
      // reconnecting to the same physical tag on the next presentation.
      try {
        isoDep.close()
      } catch (e: Exception) {
        // already disconnected
      }
    }
  }
}
