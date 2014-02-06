package molmed.utils

import java.io.File
import org.broadinstitute.sting.queue.QScript
import org.broadinstitute.sting.queue.extensions.picard.MergeSamFiles
import org.broadinstitute.sting.queue.function.ListWriterFunction
import org.broadinstitute.sting.queue.extensions.picard.SortSam
import net.sf.samtools.SAMFileHeader.SortOrder
import org.broadinstitute.sting.commandline.Input
import org.broadinstitute.sting.commandline.Output
import org.broadinstitute.sting.commandline.Argument
import org.broadinstitute.sting.queue.extensions.picard.MarkDuplicates
import org.broadinstitute.sting.queue.extensions.picard.ValidateSamFile
import org.broadinstitute.sting.queue.extensions.picard.AddOrReplaceReadGroups
import molmed.queue.extensions.picard.FixMateInformation
import org.broadinstitute.sting.queue.extensions.picard.RevertSam
import org.broadinstitute.sting.queue.extensions.picard.SamToFastq
import molmed.queue.extensions.picard.BuildBamIndex
import molmed.queue.extensions.RNAQC.RNASeQC
import org.broadinstitute.sting.queue.function.InProcessFunction
import java.io.PrintWriter
import scala.io.Source

/**
 * Assorted commandline wappers, mostly for file doing small things link indexing files. See case classes to figure out
 * what's what.
 */
class GeneralUtils(projectName: Option[String], uppmaxConfig: UppmaxConfig) extends UppmaxJob(uppmaxConfig) {

  /**
   * Creates a bam index for a bam file.
   */
  case class createIndex(@Input bam: File, @Output index: File) extends BuildBamIndex with OneCoreJob {
    this.input = bam
    this.output = index
  }

  /**
   * Joins the bam file specified to a single bam file.
   */
  case class joinBams(@Input inBams: Seq[File], @Output outBam: File) extends MergeSamFiles with OneCoreJob {
    this.input = inBams
    this.output = outBam

    override def jobRunnerJobName = projectName.get + "_joinBams"

    this.isIntermediate = false
  }

  /**
   * Writes that paths of the inBams to a file, which one file on each line.
   */
  case class writeList(inBams: Seq[File], outBamList: File) extends ListWriterFunction {
    this.inputFiles = inBams
    this.listFile = outBamList
    override def jobRunnerJobName = projectName.get + "_bamList"
  }

  /**
   * Sorts a bam file.
   */
  case class sortSam(inSam: File, outBam: File, sortOrderP: SortOrder) extends SortSam with OneCoreJob {
    this.input :+= inSam
    this.output = outBam
    this.sortOrder = sortOrderP
    override def jobRunnerJobName = projectName.get + "_sortSam"
  }

  /**
   * Runs cutadapt on a fastqfile and syncs it (adds a N to any reads which are empty after adaptor trimming).
   */
  case class cutadapt(@Input fastq: File, cutFastq: File, @Argument adaptor: String, @Argument cutadaptPath: String, @Argument syncPath: String = "resources/FixEmptyReads.pl") extends OneCoreJob {

    @Output val fastqCut: File = cutFastq
    this.isIntermediate = true
    // Run cutadapt and sync via perl script by adding N's in all empty reads.  
    def commandLine = cutadaptPath + " -a " + adaptor + " " + fastq + " | perl " + syncPath + " -o " + fastqCut
    override def jobRunnerJobName = projectName.get + "_cutadapt"
  }

  /**
   * Wraps Picard MarkDuplicates
   */
  case class dedup(inBam: File, outBam: File, metricsFile: File, asIntermediate: Boolean = false) extends MarkDuplicates with TwoCoreJob {

    this.isIntermediate = asIntermediate

    this.input :+= inBam
    this.output = outBam
    this.metrics = metricsFile
    this.memoryLimit = Some(16)
    override def jobRunnerJobName = projectName.get + "_dedup"
  }

  /**
   * Wraps Picard ValidateSamFile
   */
  case class validate(inBam: File, outLog: File, reference: File) extends ValidateSamFile with OneCoreJob {
    this.input :+= inBam
    this.output = outLog
    this.REFERENCE_SEQUENCE = reference
    this.isIntermediate = false
    override def jobRunnerJobName = projectName.get + "_validate"
  }

  /**
   * Wraps Picard FixMateInformation
   */
  case class fixMatePairs(inBam: Seq[File], outBam: File) extends FixMateInformation with OneCoreJob {
    this.input = inBam
    this.output = outBam
    override def jobRunnerJobName = projectName.get + "_fixMates"
  }

  /**
   * Wraps Picard RevertSam. Removes aligment information from bam file.
   */
  case class revert(inBam: File, outBam: File, removeAlignmentInfo: Boolean) extends RevertSam with OneCoreJob {
    this.output = outBam
    this.input :+= inBam
    this.removeAlignmentInformation = removeAlignmentInfo;
    this.sortOrder = if (removeAlignmentInfo) { SortOrder.queryname } else { SortOrder.coordinate }
    override def jobRunnerJobName = projectName.get + "_revert"

  }

  /**
   * Wraps SamToFastq. Converts a sam file to a fastq file.
   */
  case class convertToFastQ(inBam: File, outFQ: File) extends SamToFastq with OneCoreJob {
    this.input :+= inBam
    this.fastq = outFQ
    override def jobRunnerJobName = projectName.get + "_convert2fastq"
  }

  /**
   * Wrapper for rseqc calculate gene body coverage script.
   */
  case class CalculateGeneBodyCoverage(@Argument pathToScript: File, @Input bamFile: File,
    @Input referenceBed: File, @Argument outputPrefix: File, @Argument outputdir: File) extends EightCoreJob {

    @Output var outputLog: File = new File(outputdir + "/calc_genebody_coverage.log")

    this.isIntermediate = false
    override def jobRunnerJobName = projectName.get + "_gene_body_coverage"

    def commandLine = pathToScript.getAbsoluteFile() + " " +
      " -i " + bamFile.getAbsolutePath() +
      " -r " + referenceBed.getAbsolutePath() +
      " -o " + outputdir + "/" + outputPrefix +
      " 2>&1 " + outputLog.getAbsolutePath()
  }

  //samtools view -hu src/test/resources/testdata/exampleBAM.bam | samtools sort -no - - | samtools view -h -  | head -15 | samtools view -Shu - | samtools flagstat -

  /**
   * Will downsample the bam file to x reads and then get the flagstat results.
   * This is used to estimate the duplication rates in RNA data.
   */
  case class DownSampleAndFlagstat(@Argument samtoolsPath: File, bamFile: File, outputFile: File, @Argument downsampleToX: Int = 10000000) extends OneCoreJob {

    @Input val inFile: File = bamFile
    @Output val outFile: File = outputFile

    this.isIntermediate = false
    override def jobRunnerJobName = projectName.get + "_downsample_flagstat"

    val samtools = samtoolsPath.getAbsoluteFile()

    def commandLine =
      samtools + " view -hu " + inFile.getAbsolutePath() +
        " | " + samtools + " sort -no - " +  inFile.getAbsolutePath() + " | " + samtools + " view -h -  | head -" + downsampleToX + " | " + samtools + " view -Shu - | " + samtools + " flagstat - " +
        " > " + outFile.getAbsolutePath()
  }

  /**
   * Wrapper for RNA-SeQC.
   */
  case class RNA_QC(@Input bamfile: File, @Input bamIndex: File, sampleSampleID: String, rRNATargetsFile: File, downsampling: Int, referenceFile: File, outDir: File, transcriptFile: File, ph: File, pathRNASeQC: File) extends RNASeQC with ThreeCoreJob {

    @Output
    val placeHolder: File = ph

    import molmed.utils.ReadGroupUtils._

    def createRNASeQCInputString(file: File): String = {
      val sampleName = getSampleNameFromReadGroups(file)
      "\"" + sampleName + "|" + file.getAbsolutePath() + "|" + sampleSampleID + "\""
    }

    val inputString = createRNASeQCInputString(bamfile)

    this.input = inputString
    this.output = outDir
    this.reference = referenceFile
    this.transcripts = transcriptFile
    this.rRNATargetString = if (rRNATargetsFile != null) " -rRNA " + rRNATargetsFile.getAbsolutePath() + " " else ""
    this.downsampleString = if (downsampling > 0) " -d " + downsampling + " " else ""
    this.placeHolderFile = placeHolder
    this.pathToRNASeQC = pathRNASeQC

    this.isIntermediate = false
    override def jobRunnerJobName = projectName.get + "_RNA_QC"
  }

  /**
   * InProcessFunction which searches a file tree for files matching metrics.tsv
   * (the output files from RNA-SeQC) and create a file containing the results
   * from all the separate runs.
   *
   */
  case class createAggregatedMetrics(phs: Seq[File], @Input outputDir: File, @Output aggregatedMetricsFile: File) extends InProcessFunction {

    @Input
    val placeHolderSeq: Seq[File] = phs

    def run() = {

      def getFileTree(f: File): Stream[File] =
        f #:: (if (f.isDirectory) f.listFiles().toStream.flatMap(getFileTree)
        else Stream.empty)

      val writer = new PrintWriter(aggregatedMetricsFile)
      val metricsFiles = getFileTree(outputDir).filter(file => file.getName().matches("metrics.tsv"))
      val header = Source.fromFile(metricsFiles(0)).getLines.take(1).next.toString()

      writer.println(header)
      metricsFiles.foreach(file =>
        for (row <- Source.fromFile(file).getLines.drop(1))
          writer.println(row))

      writer.close()

    }
    this.jobName = aggregatedMetricsFile.getAbsolutePath()
  }
}

/**
 * Contains some general utility functions. See each description.
 */
object GeneralUtils {

  /**
   * Exchanges the extension on a file.
   * @param file File to look for the extension.
   * @param oldExtension Old extension to strip off, if present.
   * @param newExtension New extension to append.
   * @return new File with the new extension in the current directory.
   */
  def swapExt(file: File, oldExtension: String, newExtension: String) =
    new File(file.getName.stripSuffix(oldExtension) + newExtension)

  /**
   * Exchanges the extension on a file.
   * @param dir New directory for the file.
   * @param file File to look for the extension.
   * @param oldExtension Old extension to strip off, if present.
   * @param newExtension New extension to append.
   * @return new File with the new extension in dir.
   */
  def swapExt(dir: File, file: File, oldExtension: String, newExtension: String) =
    new File(dir, file.getName.stripSuffix(oldExtension) + newExtension)

  /**
   * Check that all the files that make up bwa index exist for the reference.
   */
  def checkReferenceIsBwaIndexed(reference: File): Unit = {
    assert(reference.exists(), "Could not find reference.")

    val referenceBasePath: String = reference.getAbsolutePath()
    for (fileEnding <- Seq("amb", "ann", "bwt", "pac", "sa")) {
      assert(new File(referenceBasePath + "." + fileEnding).exists(), "Could not find index file with file ending: " + fileEnding)
    }
  }

}