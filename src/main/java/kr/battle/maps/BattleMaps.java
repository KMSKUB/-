package kr.battle.maps;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 능력 배틀 맵 관리 모드 (서버 쪽에서만 동작 - 접속하는 친구들은 모드를 설치할 필요가 없다).
 *
 * 하는 일:
 *  - 야생(battle:arena), 도시 1~3(battle:city1~3) 월드를 이 모드가 직접 만들고 관리한다.
 *  - 게임에 쓴 맵은 게임이 끝나면 삭제하고,
 *      도시: 원본(battle_map_originals/cityN)의 새 복사본을 그 자리에 넣고,
 *      야생: 폴더를 지운 뒤 새 시드로 월드를 새로 만든다.
 *  - 데이터팩과는 스코어보드로만 대화한다. (README 참고)
 */
public final class BattleMaps implements ModInitializer {
	public static final String ID = "battle_maps";
	public static final Logger LOG = LoggerFactory.getLogger("BattleMaps");

	private static MapManager manager;

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			try {
				manager = new MapManager(server);
				manager.onStart();
			} catch (Throwable t) {
				LOG.error("[BattleMaps] 시작 중 오류 - 맵 관리가 꺼진 채로 계속합니다.", t);
				manager = null;
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			MapManager m = manager;
			if (m != null) {
				m.tick();
			}
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			MapManager m = manager;
			manager = null;
			if (m != null) {
				m.onStop();
			}
		});
	}
}
