"""Display budgets shared by the X11 and Wayland control backends."""
MAX_PIXELS = 8_388_608


def valid_size(width, height):
    return (type(width) is int and type(height) is int and
            1 <= width <= 4096 and 1 <= height <= 4096 and width * height <= MAX_PIXELS)


def valid_rate(width, height, millihz):
    return (valid_size(width, height) and 10_000 <= millihz <= 120_250 and
            (width * height <= 4_194_304 or millihz <= 60_250))
