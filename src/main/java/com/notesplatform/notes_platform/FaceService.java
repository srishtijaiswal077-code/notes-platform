package com.notesplatform.notes_platform;

import nu.pattern.OpenCV;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.FaceRecognizerSF;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
public class FaceService {

    private FaceDetectorYN detector;
    private FaceRecognizerSF recognizer;

    @PostConstruct
    public void init() throws IOException {
        OpenCV.loadLocally();
        String detPath = copyToTemp("models/face_detection_yunet_2023mar.onnx");
        String recPath = copyToTemp("models/face_recognition_sface_2021dec.onnx");
        detector = FaceDetectorYN.create(detPath, "", new Size(320, 320));
        detector.setScoreThreshold(0.6f);
        recognizer = FaceRecognizerSF.create(recPath, "");
    }

    private String copyToTemp(String resource) throws IOException {
        Path tmp = Files.createTempFile("model-", ".onnx");
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        tmp.toFile().deleteOnExit();
        return tmp.toString();
    }

    public synchronized float[] getEmbedding(byte[] imageBytes)  {
        Mat img = Imgcodecs.imdecode(new MatOfByte(imageBytes), Imgcodecs.IMREAD_COLOR);
        if (img.empty()) throw new IllegalArgumentException("Invalid image");

        // badi photo ko chhota karo (max 640 px)
        int maxSide = Math.max(img.cols(), img.rows());
        if (maxSide > 640) {
            double scale = 640.0 / maxSide;
            Imgproc.resize(img, img, new Size(img.cols() * scale, img.rows() * scale));
        }
        System.out.println("Image size: " + img.cols() + "x" + img.rows());

        detector.setInputSize(img.size());
        Mat faces = new Mat();
        detector.detect(img, faces);

        if (faces.rows() == 0) throw new IllegalArgumentException("No face found");
        if (faces.rows() > 1) throw new IllegalArgumentException("Multiple faces found");

        Mat aligned = new Mat();
        recognizer.alignCrop(img, faces.row(0), aligned);

        Mat feature = new Mat();
        recognizer.feature(aligned, feature);

        float[] emb = new float[128];
        feature.get(0, 0, emb);
        return emb;
    }

    public double compare(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}