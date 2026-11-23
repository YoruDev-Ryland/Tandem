using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Tandem.Server.Data.Migrations.Sqlite
{
    /// <inheritdoc />
    public partial class AddInstallGameId : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "GameId",
                table: "InstallRecords",
                type: "TEXT",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "GameId",
                table: "InstallRecords");
        }
    }
}
