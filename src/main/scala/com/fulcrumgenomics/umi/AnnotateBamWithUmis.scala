/*
 * The MIT License
 *
 * Copyright (c) 2016 Fulcrum Genomics
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */
package com.fulcrumgenomics.umi

import com.fulcrumgenomics.FgBioDef._
import com.fulcrumgenomics.bam.api.{SamSource, SamWriter}
import com.fulcrumgenomics.cmdline.{ClpGroups, FgBioTool}
import com.fulcrumgenomics.commons.io.Io
import com.fulcrumgenomics.commons.util.LazyLogging
import com.fulcrumgenomics.fastq.FastqSource
import com.fulcrumgenomics.sopt._
import com.fulcrumgenomics.util.{ProgressLogger, ReadStructure, SegmentType}

@clp(description =
  """
    |Annotates existing BAM files with UMIs (Unique Molecular Indices, aka Molecular IDs,
    |Molecular barcodes) from a separate FASTQ file. Takes an existing BAM file and a FASTQ
    |file consisting of UMI reads, matches the reads between the files based on read names,
    |and produces an output BAM file where each record is annotated with an optional tag
    |(specified by `attribute`) that contains the read sequence of the UMI.  Trailing read
    |numbers (`/1` or `/2`) are removed from FASTQ read names, as is any text after whitespace,
    |before matching.
    |
    |The `--read-structure` option may be used to specify which bases in the FASTQ contain UMI
    |bases.  Otherwise it is assumed the FASTQ contains only UMI bases.
    |
    |At the end of execution, reports how many records were processed and how many were
    |missing UMIs. If any read from the BAM file did not have a matching UMI read in the
    |FASTQ file, the program will exit with a non-zero exit status.  The `--fail-fast` option
    |may be specified to cause the program to terminate the first time it finds a records
    |without a matching UMI.
    |
    |In order to avoid sorting the input files, the entire UMI fastq file is read into
    |memory. As a result the program needs to be run with memory proportional the size of
    |the (uncompressed) fastq.
  """,
  group = ClpGroups.SamOrBam)
class AnnotateBamWithUmis(
  @arg(flag='i', doc="The input SAM or BAM file.")             val input: PathToBam,
  @arg(flag='f', doc="Input FASTQ file with UMI reads.")       val fastq: PathToFastq,
  @arg(flag='o', doc="Output BAM file to write.")              val output: PathToBam,
  @arg(flag='t', doc="The BAM attribute to store UMIs in.")    val attribute: String = "RX",
  @arg(flag='r', doc="The read structure for the FASTQ, otherwise all bases will be used.")
                                                               val readStructure: ReadStructure = ReadStructure("+M"),
  @arg(          doc="If set, fail on the first missing UMI.") val failFast: Boolean = false
) extends FgBioTool with LazyLogging {

  private var missingUmis: Long = 0

  /** Updates the count of missing UMI records, and throws an exception if fail-fast is true. */
  private def logMissingUmi(readName: String): Unit = {
    missingUmis += 1
    if (failFast) fail("Record '" + readName + "' in BAM file not found in FASTQ file.")
  }

  /** Extracts the UMI bases given the read structure */
  private def extractUmis(bases: String, structure: ReadStructure): String = {
    structure
      .extract(bases)
      .filter(_.kind == SegmentType.MolecularBarcode)
      .map(_.bases)
      .mkString("")
  }

  /** Main method that does the work of reading input files, matching up reads and writing the output file. */
  override def execute(): Unit = {
    Io.assertReadable(Seq(input, fastq))
    Io.assertCanWriteFile(output)

    // Read in the fastq file
    logger.info("Reading in UMIs from FASTQ.")
    val fqIn      = FastqSource(fastq)
    val nameToUmi =  fqIn.map(fq => (fq.name, extractUmis(fq.bases, readStructure))).toMap

    // Loop through the BAM file an annotate it
    logger.info("Reading input BAM and annotating output BAM.")
    val in       = SamSource(input)
    val out      = SamWriter(output, in.header)
    val progress = ProgressLogger(logger)

    in.foreach(rec => {
      val name = rec.name
      nameToUmi.get(name) match {
        case Some(umi) => rec(attribute) = umi
        case None      => logMissingUmi(name)
      }
      out += rec
      progress.record(rec)
    })

    // Finish up
    out.close()
    logger.info(s"Processed ${progress.getCount} records with ${missingUmis} missing UMIs.")
    if (missingUmis > 0) fail(exit=missingUmis.toInt)
  }
}
