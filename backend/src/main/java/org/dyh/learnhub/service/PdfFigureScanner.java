package org.dyh.learnhub.service;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/**
 * 扫描一页里"被真正画出来的位图"的位置，坐标是**设备坐标**（左上角原点、y 向下），
 * 和 {@link PdfLayoutExtractor} 里正文行的 y 是同一套 —— 所以图片能按 y 插回阅读顺序。
 *
 * <h3>为什么要单独一个类，而且必须继承 PDFGraphicsStreamEngine</h3>
 * 图片的位置藏在 `Do` 之前的 `cm` 里，而 `cm` 是**图形操作符**：
 * 基类 {@code PDFStreamEngine} 的构造函数一个操作符都不注册（实测重写它收不到任何回调、
 * 收到的 CTM 永远是单位阵），操作符在 {@code PDFGraphicsStreamEngine} 里才注册。
 * 于是这里继承图形引擎、只重写 {@link #drawImage(PDImage)}，其余路径/裁剪相关方法空实现。
 *
 * <p>为什么不"整页截图"：论文的图是位图与矢量图形混排的，整页截图会把两栏正文一起塞进图里。
 * 这里拿到的是单张图的包围盒，可以只裁那一块。
 */
final class PdfFigureScanner extends PDFGraphicsStreamEngine {

    private final List<double[]> rects = new ArrayList<>();

    PdfFigureScanner(PDPage page) {
        super(page);
    }

    /** 跑一遍这一页的内容流，返回所有图片的包围盒 {@code [x0, y0, x1, y1]} */
    List<double[]> scan() {
        try {
            processPage(getPage());
        } catch (Exception e) {
            // 扫不出来就当这页没图：阅读器少一张图，不该让整份文档的抽取失败
        }
        return rects;
    }

    @Override
    public void drawImage(PDImage image) {
        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
        if (ctm == null) {
            return;
        }
        // 单位正方形四个角一起变换再取包围盒：图片常带翻转/旋转，
        // 只看 translate + scalingFactor 会得到错的高宽（实测有些图是上下翻转画的）
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] c : new double[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}}) {
            Vector v = ctm.transform(new Vector((float) c[0], (float) c[1]));
            minX = Math.min(minX, v.getX());
            maxX = Math.max(maxX, v.getX());
            minY = Math.min(minY, v.getY());
            maxY = Math.max(maxY, v.getY());
        }
        // CTM 的 y 是 **PDF 用户空间**（原点在左下、y 向上），而正文行的 y 是"从页面顶部往下"
        //（PDFBox 给文本的是 getYDirAdj）。这里必须翻一下，否则图和正文整体错开半页 —— 实测踩过：
        // 某页的 teaser 图算出来在 y=468（页面下部），翻了之后才是它在页首的 y=54。
        double pageHeight = getPage().getCropBox() != null
                ? getPage().getCropBox().getHeight() : getPage().getMediaBox().getHeight();
        rects.add(new double[]{minX, pageHeight - maxY, maxX, pageHeight - minY});
    }

    // ------------------------------------------------------------------
    // 以下都是抽象方法的空实现：我们只要图片位置，路径、裁剪、填充一概不关心
    // ------------------------------------------------------------------

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
    }

    @Override
    public void clip(int windingRule) {
    }

    @Override
    public void moveTo(float x, float y) {
    }

    @Override
    public void lineTo(float x, float y) {
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
    }

    @Override
    public Point2D getCurrentPoint() {
        return new Point2D.Float(0, 0);
    }

    @Override
    public void closePath() {
    }

    @Override
    public void endPath() {
    }

    @Override
    public void strokePath() {
    }

    @Override
    public void fillPath(int windingRule) {
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
    }

    @Override
    public void shadingFill(COSName shadingName) {
    }
}
