package mycnn

import java.io.Serializable
import breeze.linalg.{DenseVector => BDV, DenseMatrix => BDM, sum, normalize, kron}
import breeze.numerics.{digamma, exp, abs}
import breeze.stats.distributions.{Gamma, RandBasis}


object Layer {
  var recordInBatch: Int = 0

  /**
   * 准备下一个batch的训练
   */
  def prepareForNewBatch {
    recordInBatch = 0
  }

  /**
   * 准备下一条记录的训练
   */
  def prepareForNewRecord {
    recordInBatch += 1
  }

  /**
   * 初始化输入层
   *
   * @param mapSize
   * @return
   */
  def buildInputLayer(mapSize: Size): Layer = {
    val layer: Layer = new InputLayer
    layer.layerType = "input"
    layer.outMapNum = 1
    layer.setMapSize(mapSize)
    return layer
  }


  def buildConvLayer(outMapNum: Int, kernelSize: Size): Layer = {
    val layer: Layer = new ConvLayer
    layer.layerType = "conv"
    layer.outMapNum = outMapNum
    layer.kernelSize = kernelSize
    return layer
  }


  def buildSampLayer(scaleSize: Size): Layer = {
    val layer: Layer = new SampLayer
    layer.layerType = "samp"
    layer.scaleSize = scaleSize
    return layer
  }


  def buildOutputLayer(classNum: Int): Layer = {
    val layer: Layer = new OutputLayer
    layer.classNum = classNum
    layer.layerType = "output"
    layer.mapSize = new Size(1, 1)
    layer.outMapNum = classNum
    return layer
  }
}



/**
 * 卷积核或者采样层scale的大小,长与宽可以不等.类型安全，定以后不可修改
 *
 * @author jiqunpeng
 *
 *         创建时间：2014-7-8 下午4:11:00
 */
class Size extends Serializable {
  final var x: Int = 0
  final var y: Int = 0

  def this(x: Int, y: Int) {
    this()
    this.x = x
    this.y = y
  }

  override def toString: String = {
    val s: StringBuilder = new StringBuilder("Size(").append(" x = ").append(x).append(" y= ").append(y).append(")")
    return s.toString
  }

  /**
   * 整除scaleSize得到一个新的Size，要求this.x、this.
   * y能分别被scaleSize.x、scaleSize.y整除
   *
   * @param scaleSize
   * @return
   */
  def divide(scaleSize: Size): Size = {
    val x: Int = this.x / scaleSize.x
    val y: Int = this.y / scaleSize.y
    if (x * scaleSize.x != this.x || y * scaleSize.y != this.y) throw new RuntimeException(this + "不能整除" + scaleSize)
    return new Size(x, y)
  }

  /**
   * 减去size大小，并x和y分别附加一个值append
   *
   * @param size
   * @param append
   * @return
   */
  def subtract(size: Size, append: Int): Size = {
    val x: Int = this.x - size.x + append
    val y: Int = this.y - size.y + append
    return new Size(x, y)
  }
}

class Layer extends Serializable {

  private var layerType: String = null
  var outMapNum: Int = 0
  private var mapSize: Size = null
  private var kernelSize: Size = null
  private var scaleSize: Size = null
  private var kernel: Array[Array[BDM[Double]]] = null
  private var bias: BDV[Double] = null
  private var outMaps: Array[Array[BDM[Double]]] = null
  private var errors: Array[Array[BDM[Double]]] = null
  private var classNum: Int = -1

  /**
   * 获取map的大小
   *
   * @return
   */
  def getMapSize: Size = {
    return mapSize
  }

  /**
   * 设置map的大小
   *
   * @param mapSize
   */
  def setMapSize(mapSize: Size) {
    this.mapSize = mapSize
  }

  /**
   * 获取层的类型
   *
   * @return
   */
  def getType: String = {
    return layerType
  }

  /**
   * 获取卷积核的大小，只有卷积层有kernelSize，其他层均未null
   *
   * @return
   */
  def getKernelSize: Size = {
    return kernelSize
  }

  /**
   * 获取采样大小，只有采样层有scaleSize，其他层均未null
   *
   * @return
   */
  def getScaleSize: Size = {
    return scaleSize
  }

  /**
   * 随机初始化卷积核
   *
   * @param frontMapNum
   */
  def initKernel(frontMapNum: Int) {
    this.kernel = Array.ofDim[BDM[Double]](frontMapNum, outMapNum)
    for (i <- 0 until frontMapNum)
      for (j <- 0 until outMapNum)
        kernel(i)(j) = Util.randomMatrix(kernelSize.x, kernelSize.y)
  }

  /**
   * 输出层的卷积核的大小是上一层的map大小
   *
   * @param frontMapNum
   * @param size
   */
  def initOutputKerkel(frontMapNum: Int, size: Size) {
    kernelSize = size
    this.kernel = Array.ofDim[BDM[Double]](frontMapNum, outMapNum)

    var i: Int = 0
    while (i < frontMapNum) {
      {
        var j: Int = 0
        while (j < outMapNum) {
          kernel(i)(j) = Util.randomMatrix(kernelSize.x, kernelSize.y)
          j += 1
        }
      }
      i += 1
    }
  }

  /**
   * 初始化偏置
   *
   * @param frontMapNum
   */
  def initBias(frontMapNum: Int) {
    this.bias = Util.randomArray(outMapNum)
  }

  /**
   * 初始化输出map
   *
   * @param batchSize
   */
  def initOutmaps(batchSize: Int) {
    outMaps = Array.ofDim[BDM[Double]](batchSize, outMapNum)
    for (i <- 0 until batchSize)
      for (j <- 0 until outMapNum) {
        outMaps(i)(j) = new BDM[Double](mapSize.x, mapSize.y)
      }
  }

  /**
   * 设置map值
   *
   * @param mapNo
	 * 第几个map
   * @param mapX
	 * map的高
   * @param mapY
	 * map的宽
   * @param value
   */
  def setMapValue(mapNo: Int, mapX: Int, mapY: Int, value: Double) {
    val m = outMaps(Layer.recordInBatch)(mapNo)
    m(mapX, mapY) = value
  }

  /**
   * 以矩阵形式设置第mapNo个map的值
   *
   * @param mapNo
   * @param outMatrix
   */
  def setMapValue(mapNo: Int, outMatrix: BDM[Double]) {
    outMaps(Layer.recordInBatch)(mapNo) = outMatrix
  }

  /**
   * 获取前一层第i个map到当前层第j个map的卷积核
   *
   * @param i
	 * 上一层的map下标
   * @param j
	 * 当前层的map下标
   * @return
   */
  def getKernel(i: Int, j: Int): BDM[Double] = {
    return kernel(i)(j)
  }

  /**
   * 设置残差值
   *
   * @param mapNo
   * @param mapX
   * @param mapY
   * @param value
   */
  def setError(mapNo: Int, mapX: Int, mapY: Int, value: Double) {
    val m = errors(Layer.recordInBatch)(mapNo)
    m(mapX, mapY) = value
  }

  /**
   * 以map矩阵块形式设置残差值
   *
   * @param mapNo
   * @param matrix
   */
  def setError(mapNo: Int, matrix: BDM[Double]) {
    errors(Layer.recordInBatch)(mapNo) = matrix
  }

  /**
   * 获取所有(每个记录和每个map)的残差
   *
   * @return
   */
  def getErrors: Array[Array[BDM[Double]]] = {
    return errors
  }

  /**
   * 初始化残差数组
   *
   * @param batchSize
   */
  def initErrors(batchSize: Int) {
    errors = Array.ofDim[BDM[Double]](batchSize, outMapNum)
    for (i <- 0 until batchSize)
      for (j <- 0 until outMapNum) {
        errors(i)(j) = new BDM[Double](mapSize.x, mapSize.y)
      }
  }

  /**
   *
   * @param lastMapNo
   * @param mapNo
   * @param kernel
   */
  def setKernel(lastMapNo: Int, mapNo: Int, kernel: BDM[Double]) {
    this.kernel(lastMapNo)(mapNo) = kernel
  }

  /**
   * 获取第mapNo个
   *
   * @param mapNo
   * @return
   */
  def getBias(mapNo: Int): Double = {
    return bias(mapNo)
  }

  /**
   * 设置第mapNo个map的偏置值
   *
   * @param mapNo
   * @param value
   */
  def setBias(mapNo: Int, value: Double) {
    bias(mapNo) = value
  }

  /**
   * 获取第recordId记录下第mapNo的残差
   *
   * @param recordId
   * @param mapNo
   * @return
   */
  def getError(recordId: Int, mapNo: Int): BDM[Double] = {
    return errors(recordId)(mapNo)
  }

  /**
   * 获取第recordId记录下第mapNo的输出map
   *
   * @param recordId
   * @param mapNo
   * @return
   */
  def getMap(recordId: Int, mapNo: Int): BDM[Double] = {
    return outMaps(recordId)(mapNo)
  }
}

class InputLayer extends Layer{

}

class ConvLayer extends Layer{

}

class SampLayer extends Layer{

}

class OutputLayer extends Layer{

}