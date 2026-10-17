package kr.battle.maps;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

/** 맵 하나(= 월드 하나)의 정보와 현재 상태. */
final class MapSlot {
	enum Phase {
		/** 월드가 열려 있지 않음 (시작 직후 / 오류) */
		CLOSED,
		/** 월드가 열려 있고 사용 가능 */
		OPEN,
		/** 월드를 닫고 폴더를 지우는 중 */
		DELETING,
		/** 삭제된 옛 월드 객체를 닫아서 밀린 파일 쓰기를 마무리하는 중 (다른 스레드) */
		CLOSING,
		/** 원본 복사본을 폴더에 넣는 중 (다른 스레드에서 복사) */
		COPYING
	}

	/** 데이터팩의 #map 값 (1 = 야생, 2~4 = 도시 1~3) */
	final int mapIndex;
	/** 월드 이름 (battle:<name>) - arena, city1, city2, city3 */
	final String name;
	/** 야생이면 true (원본 없이 새로 생성), 도시면 false (원본 복사) */
	final boolean wild;
	/** 지형 생성기 JSON (데이터팩 dimension 파일의 "generator" 부분과 같은 형식) */
	final String generatorJson;
	final Identifier id;
	final ResourceKey<Level> levelKey;

	/** 이 맵 월드의 저장 폴더 (게임이 실제로 쓰는 위치) */
	Path dir;

	/** true = 폴더 내용이 '깨끗한 새 복사본'이 아님 (게임에 쓰였거나, 아직 한 번도 초기화되지 않음) */
	boolean dirty;
	Phase phase = Phase.CLOSED;
	RuntimeLevelHandle handle;
	CompletableFuture<Void> copy;
	/** 삭제를 요청한 월드 객체 (삭제된 뒤 닫아서 열려 있던 파일을 정리한다) */
	ServerLevel oldLevel;
	CompletableFuture<Void> closing;
	/** 이번 복사에 쓰는 원본 폴더 (region 폴더가 들어 있는 폴더) */
	Path source;
	/** DELETING / CLOSING 에서 기다린 틱 수 */
	int waitTicks;
	/** 실패 뒤 재시도까지 남은 틱 수 */
	int cooldown;
	/** 데이터팩에 같은 이름의 dimension 파일이 있어 이 모드가 관리하지 않는 맵 */
	boolean unmanaged;
	/** 쓴 맵인데 원본에서 region 폴더를 못 찾아 초기화하지 못하는 중 */
	boolean sourceProblem;
	/** 위 문제를 이미 로그로 알렸는지 (같은 경고 반복 방지) */
	boolean sourceWarned;
	/** 게임의 월드 삭제가 덜 끝나서(파일 잠금 등) 서버 종료 때 폴더가 지워질 수 있음 -> 다음 실행에 다시 초기화 */
	boolean exitRisk;

	MapSlot(int mapIndex, String name, boolean wild, String generatorJson) {
		this.mapIndex = mapIndex;
		this.name = name;
		this.wild = wild;
		this.generatorJson = generatorJson;
		this.id = Identifier.fromNamespaceAndPath("battle", name);
		this.levelKey = ResourceKey.create(Registries.DIMENSION, this.id);
	}
}
