package com.rfl.replay;

/** Where the server says one player is this game tick: the local x/y of the true tile's centre. */
public final class TrueTile
{
    final String name;
    final int x;
    final int y;

    TrueTile(String name, int x, int y)
    {
        this.name = name;
        this.x = x;
        this.y = y;
    }
}
