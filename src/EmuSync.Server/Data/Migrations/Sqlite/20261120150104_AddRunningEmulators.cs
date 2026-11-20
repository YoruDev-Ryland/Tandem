using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace EmuSync.Server.Data.Migrations.Sqlite
{
    /// <inheritdoc />
    public partial class AddRunningEmulators : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "RunningEmulatorsJson",
                table: "Devices",
                type: "TEXT",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "RunningEmulatorsJson",
                table: "Devices");
        }
    }
}
