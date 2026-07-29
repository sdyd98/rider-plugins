// Inheritance, nesting, and the shapes a game server actually has.
#pragma once

#include "Net.h"

namespace game {

struct ITickable {
    virtual ~ITickable() = default;      //= 함수
    virtual void Tick(float dt) = 0;     //= 함수
};

struct ISerializable {
    virtual ~ISerializable() = default;              //= 함수
    virtual void Write(net::Packet& out) const = 0;  //= 함수
};

class World : public ITickable, public ISerializable {
public:
    // A class nested inside a class — its methods are members of the inner one, and a declaration that
    // contains other declarations is exactly where a "does this have parameters" test goes wrong.
    class Region {
    public:
        explicit Region(int id);            //= 함수
        void Enter(net::Session& s);        //= 함수
        int Id() const { return id_; }      //= 함수

    private:
        int id_;
    };

    World();                                        //= 함수
    ~World() override;                              //= 함수

    void Tick(float dt) override;                   //= 함수
    void Write(net::Packet& out) const override;    //= 함수

    Region* Find(int id);                           //= 함수
    const Region* Find(int id) const;               //= 함수

    template <typename F>
    void ForEach(F callback);                       //= 함수

private:
    std::vector<Region*> regions_;                  //= 아님
};

}  // namespace game
