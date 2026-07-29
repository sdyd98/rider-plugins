#include "World.h"

namespace game {

World::Region::Region(int id) : id_(id) {}      //= 함수

void World::Region::Enter(net::Session& s) {    //= 함수
    s.Handle(net::Packet{1, ""});
}

World::World() {}                               //= 함수

World::~World() {                               //= 함수
    for (auto* r : regions_) delete r;
}

void World::Tick(float dt) {                    //= 함수
    (void)dt;
}

void World::Write(net::Packet& out) const {     //= 함수
    out.id = static_cast<int>(regions_.size());
}

World::Region* World::Find(int id) {            //= 함수
    for (auto* r : regions_) {
        if (r->Id() == id) return r;
    }
    return nullptr;
}

const World::Region* World::Find(int id) const {   //= 함수
    for (auto* r : regions_) {
        if (r->Id() == id) return r;
    }
    return nullptr;
}

template <typename F>
void World::ForEach(F callback) {               //= 함수
    for (auto* r : regions_) callback(*r);
}

}  // namespace game

int main() {                                    //= 함수
    game::World world;
    world.Tick(0.016f);
    return 0;
}
