package kr.battle.maps;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/** 폴더 통째로 복사 / 삭제. */
final class FileTools {
	private FileTools() {
	}

	/** src 폴더 안의 모든 파일을 dst 로 복사한다 (dst 는 없으면 만든다). session.lock 은 건너뛴다. */
	static void copyTree(Path src, Path dst) throws IOException {
		Files.createDirectories(dst);
		Files.walkFileTree(src, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (!file.getFileName().toString().equals("session.lock")) {
					Files.copy(file, dst.resolve(src.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
				}
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** 폴더를 통째로 지운다. 없으면 아무 일도 안 한다. 지우다 실패하면 false. */
	static boolean deleteTree(Path dir) {
		if (!Files.exists(dir)) {
			return true;
		}
		try {
			Files.walkFileTree(dir, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
					Files.delete(d);
					return FileVisitResult.CONTINUE;
				}
			});
			return true;
		} catch (IOException e) {
			BattleMaps.LOG.warn("[BattleMaps] 폴더를 완전히 지우지 못했습니다: {} ({})", dir, e.toString());
			return !Files.exists(dir);
		}
	}
}
